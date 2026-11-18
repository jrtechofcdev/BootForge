package dev.bootforge.app.data

import android.app.Application
import android.content.Context
import dev.bootforge.app.DownloadService
import dev.bootforge.blockdev.Progress
import dev.bootforge.catalog.CatalogDefinition
import dev.bootforge.catalog.Category
import dev.bootforge.catalog.DownloadControl
import dev.bootforge.catalog.DownloadPausedException
import dev.bootforge.catalog.DownloadRequest
import dev.bootforge.catalog.DownloadRequestJson
import dev.bootforge.catalog.Downloader
import dev.bootforge.catalog.Library
import dev.bootforge.catalog.LibraryItem
import dev.bootforge.catalog.OpenPgp
import dev.bootforge.catalog.ResolvedImage
import dev.bootforge.catalog.Resolvers
import dev.bootforge.catalog.SystemDef
import dev.bootforge.catalog.UrlConnectionHttp
import dev.bootforge.integrity.OperationCancelledException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

enum class JobStatus(val label: String) { QUEUED("Na fila"), RUNNING("Baixando"), PAUSED("Pausado"), FAILED("Falhou") }

data class JobState(val req: DownloadRequest, val status: JobStatus, val bytes: Long, val progress: Progress? = null, val message: String? = null)

/** Situação de um sistema no catálogo (consultado ou não). */
data class EntryState(val system: SystemDef, val resolved: ResolvedImage? = null, val error: String? = null, val loading: Boolean = false)

data class CenterState(
    val catalog: CatalogDefinition,
    /** "embutido no app" ou de onde veio a atualização. */
    val catalogOrigin: String,
    val entries: Map<String, EntryState>,
    val jobs: List<JobState>,
    val library: List<LibraryItem>,
    val checkedAt: Long? = null,
    val message: String? = null,
) {
    fun byCategory(c: Category?) = catalog.systems.filter { c == null || it.category == c }.map { entries.getValue(it.id) }
    val activeCount get() = jobs.count { it.status == JobStatus.RUNNING || it.status == JobStatus.QUEUED }
}

/**
 * Central do catálogo e dos downloads (Etapa 6): consulta as fontes oficiais, mantém a fila de
 * downloads (um por vez, para não dividir a banda do celular) e a biblioteca de imagens.
 * Os pedidos ficam salvos em disco: um download pausado continua mesmo depois de fechar o app.
 */
class DownloadCenter(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = app.getSharedPreferences("catalogo", Context.MODE_PRIVATE)

    /** Pasta das ISOs: área do app no armazenamento externo (não precisa de permissão). */
    val dir: File = (app.getExternalFilesDir("isos") ?: File(app.filesDir, "isos")).apply { mkdirs() }
    private val catalogFile = File(app.filesDir, "catalogo/catalog.json")
    val library = Library(dir)
    private val downloader = Downloader(dir)
    private val controls = HashMap<String, DownloadControl>()
    private val queue = Channel<String>(Channel.UNLIMITED)

    private val _state: MutableStateFlow<CenterState>
    val state: StateFlow<CenterState> get() = _state.asStateFlow()

    var catalogUrl: String
        get() = prefs.getString("url", DEFAULT_CATALOG_URL) ?: DEFAULT_CATALOG_URL
        set(v) { prefs.edit().putString("url", v).apply() }

    init {
        val (def, origin) = loadCatalog()
        _state = MutableStateFlow(CenterState(def, origin, def.systems.associate { it.id to EntryState(it) }, restoreJobs(), library.items()))
        scope.launch { for (name in queue) runJob(name) }
    }

    // ------------------------------------------------------------------ catálogo

    private fun loadCatalog(): Pair<CatalogDefinition, String> {
        val bundled = CatalogDefinition.bundled()
        val updated = runCatching { CatalogDefinition.parse(catalogFile.readText()) }.getOrNull()
        return if (updated != null && updated.updated > bundled.updated) updated to "atualizado em ${updated.updated}" else bundled to "embutido no app (${bundled.updated})"
    }

    private val keys = HashMap<String, OpenPgp.Keys>()
    private fun keysFor(group: String): OpenPgp.Keys = synchronized(keys) {
        keys.getOrPut(group) { OpenPgp.loadKeys(_state.value.catalog.keys[group] ?: error("Grupo de chaves $group ausente")) }
    }

    /** Consulta as versões atuais em todas as fontes (até 4 ao mesmo tempo). */
    fun checkVersions(ids: Collection<String>? = null) = scope.launch {
        val systems = _state.value.catalog.systems.filter { ids == null || it.id in ids }
        _state.update { s -> s.copy(entries = s.entries + systems.associate { it.id to s.entries.getValue(it.id).copy(loading = true, error = null) }) }
        val resolvers = Resolvers(UrlConnectionHttp(), ::keysFor)
        val gate = Semaphore(4)
        systems.map { sys ->
            async {
                gate.withPermit {
                    val r = runCatching { resolvers.resolve(sys) }
                    _state.update { s ->
                        s.copy(entries = s.entries + (sys.id to EntryState(sys, r.getOrNull(), r.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }, false)))
                    }
                }
            }
        }.awaitAll()
        _state.update { it.copy(checkedAt = System.currentTimeMillis()) }
    }

    /** Baixa uma versão nova do catalog.json (configuração centralizada) e passa a usá-la. */
    fun updateCatalog() = scope.launch {
        val msg = runCatching {
            val text = UrlConnectionHttp().text(catalogUrl, 2 shl 20)
            val def = CatalogDefinition.parse(text)
            def.keys.values.forEach { OpenPgp.loadKeys(it) } // só chaves que já vêm no app
            if (def.updated <= _state.value.catalog.updated) return@runCatching "O catálogo já está atualizado (${_state.value.catalog.updated})."
            catalogFile.parentFile?.mkdirs(); catalogFile.writeText(text)
            synchronized(keys) { keys.clear() }
            _state.update { s -> CenterState(def, "atualizado em ${def.updated}", def.systems.associate { it.id to EntryState(it) }, s.jobs, s.library) }
            "Catálogo atualizado para ${def.updated} (${def.systems.size} sistemas)."
        }.getOrElse { "Não foi possível atualizar o catálogo: ${it.message}. Continua valendo o catálogo atual." }
        say(msg)
    }

    // ------------------------------------------------------------------ downloads

    fun enqueue(r: ResolvedImage) {
        val req = DownloadRequest.of(r)
        library.find(req.systemId, req.version, req.checksum)?.let { say("\"${it.title} ${it.version ?: ""}\" já está na biblioteca: não é preciso baixar de novo."); return }
        if (_state.value.jobs.any { it.req.fileName == req.fileName }) { resume(req.fileName); return }
        File(dir, "${req.fileName}.req.json").writeText(DownloadRequestJson.encode(req))
        _state.update { it.copy(jobs = it.jobs + JobState(req, JobStatus.QUEUED, downloader.partialBytes(req))) }
        queue.trySend(req.fileName)
        DownloadService.start(app)
    }

    fun pause(name: String) {
        controls[name]?.pause()
        setJob(name) { if (it.status == JobStatus.QUEUED) it.copy(status = JobStatus.PAUSED) else it }
    }

    fun resume(name: String) {
        setJob(name) { it.copy(status = JobStatus.QUEUED, message = null) }
        queue.trySend(name)
        DownloadService.start(app)
    }

    fun cancel(name: String) {
        val c = controls[name]
        if (c != null) c.cancel() else {
            library.discardPartial(name)
            File(dir, "$name.req.json").delete()
            _state.update { s -> s.copy(jobs = s.jobs.filter { it.req.fileName != name }) }
        }
    }

    private fun runJob(name: String) {
        val job = _state.value.jobs.firstOrNull { it.req.fileName == name } ?: return
        if (job.status != JobStatus.QUEUED) return
        val control = DownloadControl().also { controls[name] = it }
        setJob(name) { it.copy(status = JobStatus.RUNNING, message = null) }
        try {
            val item = downloader.download(job.req, library, { p -> setJob(name) { it.copy(progress = p, bytes = p.bytesDone) } }, control)
            File(dir, "$name.req.json").delete()
            _state.update { s -> s.copy(jobs = s.jobs.filter { it.req.fileName != name }, library = library.items()) }
            AppGraph.history.add(HistoryEntry(System.currentTimeMillis(), "Download: ${item.title} ${item.version ?: ""}", "Catálogo", true, item.status.label))
            say("${item.title} ${item.version ?: ""} baixado. ${item.status.label}.")
        } catch (e: DownloadPausedException) {
            setJob(name) { it.copy(status = JobStatus.PAUSED, bytes = e.bytes, progress = null) }
        } catch (e: OperationCancelledException) {
            File(dir, "$name.req.json").delete()
            _state.update { s -> s.copy(jobs = s.jobs.filter { it.req.fileName != name }) }
        } catch (e: Exception) {
            setJob(name) { it.copy(status = JobStatus.FAILED, message = e.message ?: e.javaClass.simpleName, progress = null, bytes = downloader.partialBytes(it.req)) }
            AppGraph.history.add(HistoryEntry(System.currentTimeMillis(), "Download: ${job.req.title}", "Catálogo", false, e.message ?: ""))
        } finally {
            controls.remove(name)
        }
    }

    /** Pedidos salvos de downloads não concluídos (voltam como pausados). */
    private fun restoreJobs(): List<JobState> = dir.listFiles { f -> f.name.endsWith(".req.json") }?.mapNotNull { f ->
        runCatching { DownloadRequestJson.decode(f.readText()) }.getOrNull()?.let { JobState(it, JobStatus.PAUSED, downloader.partialBytes(it)) }
    } ?: emptyList()

    fun refreshLibrary() = _state.update { it.copy(library = library.items()) }

    fun removeFromLibrary(item: LibraryItem) { library.remove(item.fileName); refreshLibrary() }

    fun consumeMessage() = _state.update { it.copy(message = null) }
    private fun say(m: String) = _state.update { it.copy(message = m) }

    private fun setJob(name: String, f: (JobState) -> JobState) =
        _state.update { s -> s.copy(jobs = s.jobs.map { if (it.req.fileName == name) f(it) else it }) }

    companion object {
        /** Onde fica o catalog.json oficial do projeto (pode ser trocado na tela do catálogo). */
        const val DEFAULT_CATALOG_URL = "https://raw.githubusercontent.com/jrtechofcdev/BootForge/main/feature/catalog/src/main/resources/dev/bootforge/catalog/catalog.json"
    }
}
