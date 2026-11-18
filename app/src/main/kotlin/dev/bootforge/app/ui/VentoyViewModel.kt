package dev.bootforge.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.bootforge.app.data.AppGraph
import dev.bootforge.app.data.Format
import dev.bootforge.app.data.OperationObserver
import dev.bootforge.blockdev.BlockDevice
import dev.bootforge.blockdev.ProgressListener
import dev.bootforge.blockdev.slice
import dev.bootforge.fs.exfat.ExFatEntry
import dev.bootforge.fs.exfat.ExFatVolume
import dev.bootforge.integrity.CancellationSignal
import dev.bootforge.integrity.ChecksumFile
import dev.bootforge.integrity.HashAlgorithm
import dev.bootforge.integrity.Hashing
import dev.bootforge.iso.ImageCategory
import dev.bootforge.iso.ImageInfo
import dev.bootforge.iso.ImageInspector
import dev.bootforge.usb.UsbDriveInfo
import dev.bootforge.ventoy.CatalogEntry
import dev.bootforge.ventoy.CheckItem
import dev.bootforge.ventoy.GrubTheme
import dev.bootforge.ventoy.ImageCatalog
import dev.bootforge.ventoy.ThemeInfo
import dev.bootforge.ventoy.ThemeManager
import dev.bootforge.ventoy.VentoyConfigStore
import dev.bootforge.ventoy.VentoyDetector
import dev.bootforge.ventoy.VentoyInfo
import dev.bootforge.ventoy.VentoyMaintenance
import dev.bootforge.ventoy.VentoyPackage
import dev.bootforge.ventoy.VentoyPersistence
import dev.bootforge.ventoy.VentoyReleaseClient
import dev.bootforge.ventoy.VentoyUpdater
import dev.bootforge.ventoy.config.ConfigIssue
import dev.bootforge.ventoy.config.VentoyConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Ordenação do gerenciador de arquivos. */
enum class FileSort(val label: String) { NAME("Nome"), SIZE("Tamanho"), TYPE("Tipo"), DATE("Data") }

/** Conflito ao copiar para o pendrive. */
enum class ConflictPolicy(val label: String) { SKIP("Pular"), REPLACE("Substituir"), KEEP_BOTH("Manter os dois") }

data class Clipboard(val paths: List<String>, val cut: Boolean)

data class FilesState(
    val path: String = "/",
    val entries: List<ExFatEntry> = emptyList(),
    val sort: FileSort = FileSort.NAME,
    val query: String = "",
    val selection: Set<String> = emptySet(),
    val clipboard: Clipboard? = null,
    val loading: Boolean = false,
    val freeBytes: Long = 0,
    val totalBytes: Long = 0,
    /** Resultado da busca recursiva (quando há texto em [query]). */
    val searchResults: List<Pair<String, ExFatEntry>>? = null,
)

data class StorageAnalysis(val byType: List<Pair<String, Long>>, val duplicates: List<List<Pair<String, Long>>>, val files: Int)

data class IsosState(
    val entries: List<CatalogEntry> = emptyList(),
    val details: Map<String, ImageInfo> = emptyMap(),
    val loading: Boolean = false,
    /** Proposta de organização: caminho atual → caminho sugerido. */
    val proposal: List<Pair<String, String>>? = null,
)

data class ConfigState(
    val loaded: Boolean = false,
    val config: VentoyConfig? = null,
    val text: String = "",
    val issues: List<ConfigIssue> = emptyList(),
    val backups: List<VentoyConfigStore.Backup> = emptyList(),
    val dirty: Boolean = false,
)

data class ThemePreview(val themeFile: String, val theme: GrubTheme, val images: Map<String, ByteArray>, val items: List<String>)

data class ThemesState(val themes: List<ThemeInfo> = emptyList(), val loading: Boolean = false, val preview: ThemePreview? = null)

data class UpdatesState(
    val loading: Boolean = false,
    val releases: List<VentoyReleaseClient.Release>? = null,
    val error: String? = null,
    val downloaded: List<VentoyReleaseClient.Downloaded> = emptyList(),
)

data class HubState(val info: UsbDriveInfo, val ventoy: VentoyInfo, val capacityBytes: Long)

sealed interface HubLoad {
    data object Idle : HubLoad
    data object Loading : HubLoad
    data class Ready(val hub: HubState) : HubLoad
    data class Failed(val message: String) : HubLoad
}

/**
 * Lógica da aba Ventoy completa (Etapa 4): arquivos, ISOs, ventoy.json, temas, ferramentas e
 * atualizações. Toda leitura/gravação passa pela [dev.bootforge.app.data.DriveSession] (acesso
 * exclusivo) e as operações longas pelo [dev.bootforge.app.data.OperationRunner] (progresso real,
 * cancelamento, notificação e histórico).
 */
class VentoyViewModel(app: Application) : AndroidViewModel(app) {

    val drives = AppGraph.drives
    private val session = AppGraph.session
    private val ops = AppGraph.operations
    val operation = ops.state
    val embedded: VentoyPackage = AppGraph.embeddedPackage

    private val _device = MutableStateFlow<String?>(null)
    val device: StateFlow<String?> = _device.asStateFlow()

    private val _hub = MutableStateFlow<HubLoad>(HubLoad.Idle)
    val hub: StateFlow<HubLoad> = _hub.asStateFlow()

    private val _files = MutableStateFlow(FilesState())
    val files: StateFlow<FilesState> = _files.asStateFlow()

    private val _analysis = MutableStateFlow<StorageAnalysis?>(null)
    val analysis: StateFlow<StorageAnalysis?> = _analysis.asStateFlow()

    private val _isos = MutableStateFlow(IsosState())
    val isos: StateFlow<IsosState> = _isos.asStateFlow()

    private val _config = MutableStateFlow(ConfigState())
    val config: StateFlow<ConfigState> = _config.asStateFlow()

    private val _themes = MutableStateFlow(ThemesState())
    val themes: StateFlow<ThemesState> = _themes.asStateFlow()

    private val _check = MutableStateFlow<List<CheckItem>?>(null)
    val check: StateFlow<List<CheckItem>?> = _check.asStateFlow()

    private val _updates = MutableStateFlow(UpdatesState())
    val updates: StateFlow<UpdatesState> = _updates.asStateFlow()

    /** Mensagens curtas para o usuário (sucesso/erro de ações rápidas). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Pedidos de compartilhamento (a tela abre o seletor do Android). */
    private val _share = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val share: SharedFlow<Intent> = _share

    init {
        viewModelScope.launch {
            drives.drives.collect { list ->
                session.closeIfDetached(list.map { it.deviceName }.toSet())
                val cur = _device.value
                if (cur != null && list.none { it.deviceName == cur }) {
                    _hub.value = HubLoad.Failed("O pendrive foi desconectado")
                }
            }
        }
        viewModelScope.launch {
            ops.finished.collect { name -> if (name != null && name == _device.value) refreshAfterOperation() }
        }
        refreshDownloaded()
    }

    fun consumeMessage() { _message.value = null }

    private fun say(msg: String) { _message.value = msg }

    // ================================================================= dispositivo

    fun select(deviceName: String) {
        if (_device.value == deviceName && _hub.value is HubLoad.Ready) return
        _device.value = deviceName
        _files.value = FilesState()
        _isos.value = IsosState()
        _config.value = ConfigState()
        _themes.value = ThemesState()
        _check.value = null
        _analysis.value = null
        reloadHub()
    }

    fun reloadHub() {
        val name = _device.value ?: return
        val info = drives.drives.value.firstOrNull { it.deviceName == name } ?: run { _hub.value = HubLoad.Failed("Pendrive não encontrado"); return }
        if (!info.hasPermission) { _hub.value = HubLoad.Failed("Permissão USB necessária"); return }
        _hub.value = HubLoad.Loading
        viewModelScope.launch(Dispatchers.IO) {
            _hub.value = runCatching {
                session.withDevice(name) { dev -> HubState(info, VentoyDetector.inspect(dev, scanData = true), dev.sizeBytes) }
            }.fold({ HubLoad.Ready(it) }, { HubLoad.Failed(it.message ?: it.javaClass.simpleName) })
        }
    }

    private fun refreshAfterOperation() {
        reloadHub()
        if (_files.value.entries.isNotEmpty() || _files.value.path != "/") listDir(_files.value.path)
        if (_isos.value.entries.isNotEmpty()) loadCatalog()
        if (_config.value.loaded) loadConfig()
        if (_themes.value.themes.isNotEmpty()) loadThemes()
    }

    private val ready: HubState? get() = (_hub.value as? HubLoad.Ready)?.hub

    /** Abre a partição de dados (exFAT) do Ventoy dentro da sessão. */
    private suspend fun <T> withData(writable: Boolean, block: (vol: ExFatVolume, data: BlockDevice, dev: BlockDevice) -> T): T {
        val h = ready ?: throw IOException("Nenhum pendrive Ventoy selecionado")
        val v = h.ventoy
        if (!v.isVentoy || v.part1Start == null || v.part1Sectors == null) throw IOException("Ventoy não encontrado neste pendrive")
        return session.withDevice(h.info.deviceName) { dev ->
            val data = dev.slice(v.part1Start!!, v.part1Sectors!!)
            block(ExFatVolume.open(data, writable), data, dev)
        }
    }

    /** Ação rápida (sem painel de progresso): erros viram mensagem. */
    private fun quick(block: suspend () -> String?) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { block() }.onSuccess { it?.let(::say) }.onFailure { say("Erro: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    /** Operação longa (painel de progresso + notificação + histórico). */
    private fun longOp(title: String, block: suspend (CancellationSignal, OperationObserver) -> String) {
        val h = ready ?: return say("Selecione um pendrive com Ventoy")
        if (!ops.run(title, h.info.displayName, h.info.deviceName, block)) say("Aguarde a operação em andamento terminar")
    }

    // ================================================================= arquivos

    fun listDir(path: String) {
        _files.update { it.copy(loading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                withData(false) { vol, _, _ -> Triple(vol.list(path), vol.freeBytes(), vol.totalBytes) }
            }.onSuccess { (entries, free, total) ->
                _files.update { it.copy(path = path, entries = entries, loading = false, selection = emptySet(), freeBytes = free, totalBytes = total, searchResults = null, query = "") }
            }.onFailure { e ->
                _files.update { it.copy(loading = false) }
                say("Erro ao abrir pasta: ${e.message}")
            }
        }
    }

    fun setSort(sort: FileSort) = _files.update { it.copy(sort = sort) }

    fun toggleSelection(path: String) = _files.update { s ->
        s.copy(selection = if (path in s.selection) s.selection - path else s.selection + path)
    }

    fun clearSelection() = _files.update { it.copy(selection = emptySet()) }

    fun selectAll() = _files.update { s -> s.copy(selection = s.entries.map { child(s.path, it.name) }.toSet()) }

    /** Pesquisa recursiva por nome (sem diferenciar maiúsculas). */
    fun search(query: String) {
        _files.update { it.copy(query = query) }
        if (query.isBlank()) { _files.update { it.copy(searchResults = null) }; return }
        viewModelScope.launch(Dispatchers.IO) {
            val q = query.trim().lowercase()
            val found = runCatching {
                withData(false) { vol, _, _ ->
                    val out = mutableListOf<Pair<String, ExFatEntry>>()
                    vol.walk("/") { p, e -> if (e.name.lowercase().contains(q) && out.size < 500) out += p to e }
                    out
                }
            }.getOrElse { say("Erro na pesquisa: ${it.message}"); emptyList() }
            if (_files.value.query == query) _files.update { it.copy(searchResults = found) }
        }
    }

    fun mkdir(name: String) = quick {
        val target = child(_files.value.path, name.trim())
        withData(true) { vol, _, _ -> vol.mkdir(target) }
        listDir(_files.value.path); "Pasta criada"
    }

    fun newTextFile(name: String, content: String) = quick {
        val target = child(_files.value.path, name.trim())
        val bytes = content.toByteArray(Charsets.UTF_8)
        withData(true) { vol, _, _ -> vol.writeFile(target, bytes.inputStream(), bytes.size.toLong()) }
        listDir(_files.value.path); "Arquivo criado"
    }

    fun rename(path: String, newName: String) = quick {
        withData(true) { vol, _, _ -> vol.rename(path, newName.trim()) }
        listDir(_files.value.path); "Renomeado"
    }

    fun delete(paths: List<String>) = longOp("Excluir ${paths.size} item(ns)") { cancel, obs ->
        withData(true) { vol, _, _ ->
            paths.forEachIndexed { i, p ->
                cancel.throwIfCancelled()
                obs.step("Excluindo ${p.substringAfterLast('/')}", i, paths.size)
                vol.delete(p, recursive = true)
            }
        }
        "${paths.size} item(ns) excluído(s)"
    }

    fun copyToClipboard(cut: Boolean) = _files.update { s -> s.copy(clipboard = Clipboard(s.selection.toList(), cut), selection = emptySet()) }

    fun paste() {
        val clip = _files.value.clipboard ?: return
        val dest = _files.value.path
        longOp(if (clip.cut) "Mover ${clip.paths.size} item(ns)" else "Copiar ${clip.paths.size} item(ns)") { cancel, obs ->
            withData(true) { vol, _, _ ->
                clip.paths.forEachIndexed { i, p ->
                    cancel.throwIfCancelled()
                    obs.step("${if (clip.cut) "Movendo" else "Copiando"} ${p.substringAfterLast('/')}", i, clip.paths.size)
                    if (clip.cut) vol.move(p, dest) else vol.copy(p, dest, listener = { obs.onProgress(it) }, cancel = cancel)
                }
            }
            _files.update { it.copy(clipboard = null) }
            "Concluído: ${clip.paths.size} item(ns) em $dest"
        }
    }

    /** Copia arquivos do celular para a pasta atual do pendrive. */
    fun importFiles(uris: List<Uri>, policy: ConflictPolicy, destDir: String = _files.value.path) =
        longOp("Copiar ${uris.size} arquivo(s) para o pendrive") { cancel, obs ->
            val resolver = getApplication<Application>().contentResolver
            val items = uris.map { u -> Triple(u, displayName(u) ?: "arquivo", size(u) ?: throw IOException("Tamanho desconhecido: $u")) }
            var copied = 0
            withData(true) { vol, _, _ ->
                val total = items.sumOf { it.third }
                if (total > vol.freeBytes()) throw IOException("Espaço insuficiente: precisa de ${Format.bytes(total)}, livre ${Format.bytes(vol.freeBytes())}")
                items.forEachIndexed { i, (uri, name, size) ->
                    cancel.throwIfCancelled()
                    val target = resolveConflict(vol, child(destDir, name), policy) ?: return@forEachIndexed
                    obs.step("Copiando $name (${Format.bytes(size)})", i, items.size)
                    resolver.openInputStream(uri)?.use { input ->
                        val r = vol.writeFile(target, input.buffered(1 shl 20), size, overwrite = policy == ConflictPolicy.REPLACE,
                            listener = { obs.onProgress(it) }, cancel = cancel)
                        obs.onLog("$name → $target (SHA-256 ${r.sha256.take(16)}…${if (r.fragments > 1) ", fragmentado em ${r.fragments}" else ""})")
                    } ?: throw IOException("Não foi possível abrir $name")
                    copied++
                }
            }
            "$copied de ${items.size} arquivo(s) copiado(s) para $destDir"
        }

    /** Copia uma pasta inteira do celular (com subpastas). */
    fun importFolder(tree: Uri, policy: ConflictPolicy) {
        val dest = _files.value.path
        longOp("Copiar pasta para o pendrive") { cancel, obs ->
            val ctx = getApplication<Application>()
            val root = DocumentFile.fromTreeUri(ctx, tree) ?: throw IOException("Pasta inválida")
            val all = mutableListOf<Pair<DocumentFile, String>>()
            fun collect(d: DocumentFile, rel: String) {
                d.listFiles().forEach { f -> if (f.isDirectory) collect(f, "$rel/${f.name}") else all += f to "$rel/${f.name}" }
            }
            collect(root, "/${root.name ?: "pasta"}")
            var n = 0
            withData(true) { vol, _, _ ->
                val total = all.sumOf { it.first.length() }
                if (total > vol.freeBytes()) throw IOException("Espaço insuficiente: precisa de ${Format.bytes(total)}")
                all.forEachIndexed { i, (doc, rel) ->
                    cancel.throwIfCancelled()
                    val target = dest.trimEnd('/') + rel
                    vol.mkdirs(target.substringBeforeLast('/'))
                    val finalPath = resolveConflict(vol, target, policy) ?: return@forEachIndexed
                    obs.step("Copiando ${doc.name}", i, all.size)
                    ctx.contentResolver.openInputStream(doc.uri)?.use { input ->
                        vol.writeFile(finalPath, input.buffered(1 shl 20), doc.length(), overwrite = policy == ConflictPolicy.REPLACE,
                            listener = { obs.onProgress(it) }, cancel = cancel)
                    }
                    n++
                }
            }
            "$n arquivo(s) copiado(s) para $dest"
        }
    }

    /** Copia itens do pendrive para uma pasta do celular (escolhida pelo usuário). */
    fun export(paths: List<String>, tree: Uri) = longOp("Copiar ${paths.size} item(ns) para o celular") { cancel, obs ->
        val ctx = getApplication<Application>()
        val root = DocumentFile.fromTreeUri(ctx, tree) ?: throw IOException("Pasta de destino inválida")
        var n = 0
        withData(false) { vol, _, _ ->
            fun copyItem(path: String, target: DocumentFile) {
                cancel.throwIfCancelled()
                val e = vol.resolve(path) ?: throw IOException("Não encontrado: $path")
                if (e.isDirectory) {
                    val sub = target.findFile(e.name)?.takeIf { it.isDirectory } ?: target.createDirectory(e.name)
                        ?: throw IOException("Não foi possível criar a pasta ${e.name}")
                    vol.list(path).forEach { copyItem(child(path, it.name), sub) }
                } else {
                    obs.step("Copiando ${e.name} (${Format.bytes(e.size)})", n, paths.size)
                    val f = target.createFile("application/octet-stream", e.name) ?: throw IOException("Não foi possível criar ${e.name}")
                    val tracker = dev.bootforge.blockdev.ProgressTracker("Copiando", e.size, ProgressListener { obs.onProgress(it) })
                    vol.openFile(path).use { input ->
                        ctx.contentResolver.openOutputStream(f.uri)?.use { out ->
                            val buf = ByteArray(1 shl 20); var done = 0L
                            while (true) { cancel.throwIfCancelled(); val r = input.read(buf); if (r < 0) break; out.write(buf, 0, r); done += r; tracker.update(done) }
                        } ?: throw IOException("Não foi possível gravar ${e.name}")
                    }
                    n++
                }
            }
            paths.forEach { copyItem(it, root) }
        }
        "$n arquivo(s) copiado(s) para o celular"
    }

    /** Compartilha um arquivo (copiado para o cache do app). */
    fun shareFile(path: String) = longOp("Preparar para compartilhar") { cancel, obs ->
        val ctx = getApplication<Application>()
        val dir = File(ctx.cacheDir, "share").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, path.substringAfterLast('/'))
        withData(false) { vol, _, _ ->
            val e = vol.resolve(path) ?: throw IOException("Não encontrado")
            if (e.size > (2L shl 30)) throw IOException("Arquivo grande demais para compartilhar (máx. 2 GiB)")
            if (e.size > ctx.cacheDir.usableSpace) throw IOException("Espaço insuficiente no celular")
            obs.step("Copiando ${e.name}", 0, 1)
            val tracker = dev.bootforge.blockdev.ProgressTracker("Copiando", e.size, ProgressListener { obs.onProgress(it) })
            vol.openFile(path).use { input -> out.outputStream().use { o ->
                val buf = ByteArray(1 shl 20); var done = 0L
                while (true) { cancel.throwIfCancelled(); val r = input.read(buf); if (r < 0) break; o.write(buf, 0, r); done += r; tracker.update(done) }
            } }
        }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", out)
        _share.tryEmit(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Compartilhar ${out.name}"))
        "Pronto para compartilhar"
    }

    /** Propriedades detalhadas (tamanho, datas, fragmentação). */
    suspend fun properties(path: String): Map<String, String> = withData(false) { vol, _, _ ->
        val e = vol.resolve(path) ?: throw IOException("Não encontrado")
        val ext = runCatching { vol.extents(path) }.getOrDefault(emptyList())
        linkedMapOf(
            "Nome" to e.name, "Caminho" to path, "Tipo" to if (e.isDirectory) "Pasta" else typeLabel(e.name),
            "Tamanho" to "${Format.bytes(e.size)} (${e.size} bytes)",
            "Modificado" to (e.modifiedMillis?.let(Format::dateTime) ?: "—"),
            "Criado" to (e.createdMillis?.let(Format::dateTime) ?: "—"),
            "Alocação" to if (e.size == 0L) "vazio" else if (ext.size <= 1) "contígua (1 trecho)" else "fragmentada (${ext.size} trechos)",
            "Somente leitura" to if (e.isReadOnly) "sim" else "não",
        )
    }

    /** Espaço por tipo + arquivos duplicados (mesmo tamanho e mesmo SHA-256). */
    fun analyze() = longOp("Analisar armazenamento") { cancel, obs ->
        val result = withData(false) { vol, _, _ ->
            val byType = HashMap<String, Long>()
            val bySize = HashMap<Long, MutableList<String>>()
            var count = 0
            obs.step("Percorrendo pastas", 0, 2)
            vol.walk("/") { p, e ->
                if (e.isDirectory) return@walk
                count++
                byType.merge(typeLabel(e.name), e.size, Long::plus)
                if (e.size > 0) bySize.getOrPut(e.size) { mutableListOf() } += p
            }
            obs.step("Procurando duplicados (SHA-256)", 1, 2)
            val dups = mutableListOf<List<Pair<String, Long>>>()
            val candidates = bySize.filter { it.value.size > 1 }
            for ((size, list) in candidates) {
                cancel.throwIfCancelled()
                list.groupBy { p -> vol.openFile(p).use { Hashing.hash(it, cancel = cancel) } }
                    .values.filter { it.size > 1 }.forEach { g -> dups += g.map { it to size } }
            }
            StorageAnalysis(byType.entries.sortedByDescending { it.value }.map { it.key to it.value }, dups, count)
        }
        _analysis.value = result
        "${result.files} arquivo(s) analisado(s); ${result.duplicates.size} grupo(s) de duplicados"
    }

    fun clearAnalysis() { _analysis.value = null }

    private fun resolveConflict(vol: ExFatVolume, path: String, policy: ConflictPolicy): String? {
        if (!vol.exists(path)) return path
        return when (policy) {
            ConflictPolicy.SKIP -> null
            ConflictPolicy.REPLACE -> path
            ConflictPolicy.KEEP_BOTH -> {
                val dir = path.substringBeforeLast('/')
                val name = path.substringAfterLast('/')
                val base = name.substringBeforeLast('.', name)
                val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
                (2..999).map { "$dir/$base ($it)$ext" }.first { !vol.exists(it) }
            }
        }
    }

    // ================================================================= ISOs

    fun loadCatalog() {
        _isos.update { it.copy(loading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                withData(false) { vol, _, _ -> ImageCatalog.scan(vol, ImageCatalog.rulesFrom(VentoyConfigStore(vol).load().first, vol)) }
            }.onSuccess { list -> _isos.update { it.copy(entries = list, loading = false) } }
                .onFailure { e -> _isos.update { it.copy(loading = false) }; say("Erro ao listar imagens: ${e.message}") }
        }
    }

    /** Analisa o conteúdo de uma imagem (sistema, boot BIOS/UEFI, truncada…). */
    fun inspectImage(path: String) = quick {
        val info = withData(false) { vol, _, _ -> vol.openReader(path).use { ImageInspector.inspect(path.substringAfterLast('/'), it) } }
        _isos.update { it.copy(details = it.details + (path to info)) }
        null
    }

    /** Verifica a integridade: SHA-256 (comparado com valor informado ou arquivo de checksum ao lado) + estrutura. */
    fun verifyImage(path: String, expected: String?) = longOp("Verificar ${path.substringAfterLast('/')}") { cancel, obs ->
        withData(false) { vol, _, _ ->
            val e = vol.resolve(path) ?: throw IOException("Não encontrado")
            obs.step("Analisando estrutura", 0, 2)
            val info = vol.openReader(path).use { ImageInspector.inspect(e.name, it) }
            _isos.update { it.copy(details = it.details + (path to info)) }
            obs.step("Calculando SHA-256 (${Format.bytes(e.size)})", 1, 2)
            val tracker = dev.bootforge.blockdev.ProgressTracker("SHA-256", e.size, ProgressListener { obs.onProgress(it) })
            val sha = vol.openFile(path).use { Hashing.hash(it, HashAlgorithm.SHA256, cancel) { done -> tracker.update(done) } }
            obs.onLog("SHA-256: $sha")
            val reference = expected?.trim()?.takeIf { it.length == 64 }?.let { it to "valor informado" }
                ?: sidecarChecksum(vol, path)
            val structure = if (info.problems.isEmpty()) "estrutura OK (${info.format.label})" else "PROBLEMAS: ${info.problems.joinToString("; ")}"
            when {
                reference == null -> "SHA-256 calculado: $sha. Sem checksum oficial para comparar — autenticidade NÃO verificada. $structure."
                Hashing.matches(reference.first, sha) -> "SHA-256 CONFERE com ${reference.second}. $structure."
                else -> throw IOException("SHA-256 NÃO confere com ${reference.second}: arquivo corrompido ou diferente do original")
            }
        }
    }

    private fun sidecarChecksum(vol: ExFatVolume, path: String): Pair<String, String>? {
        val dir = path.substringBeforeLast('/').ifEmpty { "/" }
        val name = path.substringAfterLast('/')
        val candidates = vol.list(dir).filter { !it.isDirectory && it.size < 1_000_000 }.filter {
            val n = it.name.lowercase()
            n == "$name.sha256".lowercase() || n == "$name.sha256sum".lowercase() || n == "sha256sums" || n == "sha256sums.txt" || n == "sha256sum.txt" || n.endsWith("-checksum") || n == "checksum"
        }
        for (c in candidates) {
            val text = vol.openFile(child(dir, c.name)).use { String(it.readBytes(), Charsets.UTF_8) }
            val entries = ChecksumFile.parse(text)
            ChecksumFile.find(entries, name)?.let { return it.hash to "arquivo ${c.name}" }
            Regex("^([0-9a-fA-F]{64})\\s*$").find(text.trim())?.let { return it.groupValues[1] to "arquivo ${c.name}" }
        }
        return null
    }

    /** Copia ISOs do celular, opcionalmente conferindo o SHA-256 informado. */
    fun addImages(uris: List<Uri>, destDir: String, expectedSha: String?) =
        longOp("Adicionar ${uris.size} imagem(ns)") { cancel, obs ->
            val resolver = getApplication<Application>().contentResolver
            var n = 0
            withData(true) { vol, _, _ ->
                vol.mkdirs(destDir)
                uris.forEachIndexed { i, uri ->
                    val name = displayName(uri) ?: "imagem.iso"
                    val size = size(uri) ?: throw IOException("Tamanho desconhecido de $name")
                    if (size > vol.freeBytes()) throw IOException("Espaço insuficiente para $name")
                    val target = child(destDir, name)
                    if (vol.exists(target)) throw IOException("Já existe $target")
                    obs.step("Copiando $name (${Format.bytes(size)})", i, uris.size)
                    val r = resolver.openInputStream(uri)?.use { vol.writeFile(target, it.buffered(1 shl 20), size, listener = { p -> obs.onProgress(p) }, cancel = cancel) }
                        ?: throw IOException("Não foi possível abrir $name")
                    obs.onLog("$name: SHA-256 ${r.sha256}${if (r.fragments > 1) " (fragmentado: ${r.fragments} trechos)" else " (contíguo)"}")
                    if (expectedSha != null && uris.size == 1 && !Hashing.matches(expectedSha, r.sha256)) {
                        vol.delete(target)
                        throw IOException("SHA-256 do arquivo não confere com o informado — a cópia foi removida")
                    }
                    n++
                }
            }
            "$n imagem(ns) adicionada(s) em $destDir"
        }

    fun moveImage(path: String, destDir: String) = quick {
        withData(true) { vol, _, _ -> vol.mkdirs(destDir); vol.move(path, destDir) }
        loadCatalog(); "Movido para $destDir"
    }

    fun renameImage(path: String, newName: String) = quick {
        withData(true) { vol, _, _ -> vol.rename(path, newName) }
        loadCatalog(); "Renomeado"
    }

    fun copyImage(path: String, destDir: String) = longOp("Copiar ${path.substringAfterLast('/')}") { cancel, obs ->
        withData(true) { vol, _, _ -> vol.mkdirs(destDir); vol.copy(path, destDir, listener = { obs.onProgress(it) }, cancel = cancel) }
        "Copiado para $destDir"
    }

    fun deleteImage(path: String) = quick {
        withData(true) { vol, _, _ -> vol.delete(path) }
        loadCatalog(); "Imagem excluída"
    }

    /** Monta uma proposta de organização por categoria (nada é movido sem confirmação). */
    fun proposeOrganization() = longOp("Analisar imagens para organizar") { cancel, obs ->
        val list = _isos.value.entries.ifEmpty {
            withData(false) { vol, _, _ -> ImageCatalog.scan(vol, ImageCatalog.rulesFrom(VentoyConfigStore(vol).load().first, vol)) }
        }
        val proposal = mutableListOf<Pair<String, String>>()
        withData(false) { vol, _, _ ->
            list.filter { it.type == "iso" || it.type == "img" || it.type == "wim" }.forEachIndexed { i, e ->
                cancel.throwIfCancelled()
                obs.step("Identificando ${e.name}", i, list.size)
                val info = runCatching { vol.openReader(e.path).use { ImageInspector.inspect(e.name, it) } }.getOrNull() ?: return@forEachIndexed
                _isos.update { it.copy(details = it.details + (e.path to info)) }
                val cat: ImageCategory = info.os?.category ?: return@forEachIndexed
                val target = "/${cat.folder}/${e.name}"
                if (!e.path.equals(target, ignoreCase = true) && !e.folder.equals("/${cat.folder}", ignoreCase = true)) proposal += e.path to target
            }
        }
        _isos.update { it.copy(proposal = proposal) }
        if (proposal.isEmpty()) "Nenhuma sugestão: as imagens identificadas já estão organizadas (ou não foram reconhecidas)"
        else "${proposal.size} sugestão(ões) de organização — revise e confirme"
    }

    fun applyOrganization(selected: List<Pair<String, String>>) = longOp("Organizar ${selected.size} imagem(ns)") { cancel, obs ->
        withData(true) { vol, _, _ ->
            selected.forEachIndexed { i, (from, to) ->
                cancel.throwIfCancelled()
                obs.step("Movendo ${from.substringAfterLast('/')}", i, selected.size)
                vol.mkdirs(to.substringBeforeLast('/'))
                if (vol.exists(to)) obs.onLog("Pulado (já existe): $to") else vol.move(from, to.substringBeforeLast('/'))
            }
        }
        _isos.update { it.copy(proposal = null) }
        "Imagens organizadas (nenhum dado foi copiado: apenas movidas)"
    }

    fun dismissProposal() = _isos.update { it.copy(proposal = null) }

    // ================================================================= ventoy.json

    fun loadConfig() = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            withData(false) { vol, _, _ ->
                val store = VentoyConfigStore(vol)
                val text = store.readText() ?: ""
                val (cfg, syntax) = if (text.isEmpty()) VentoyConfig.empty() to emptyList() else VentoyConfig.parse(text)
                ConfigState(true, cfg, text.ifEmpty { "{\n}\n" }, syntax + (cfg?.let(store::validate) ?: emptyList()), store.backups(), false)
            }
        }.onSuccess { _config.value = it }.onFailure { say("Erro ao ler ventoy.json: ${it.message}") }
    }

    /** Edição visual: aplica a nova configuração (ainda não salva). */
    fun editConfig(cfg: VentoyConfig) {
        _config.update { it.copy(config = cfg, text = cfg.toJsonText(), dirty = true) }
        revalidate()
    }

    /** Edição avançada (texto). */
    fun editText(text: String) {
        val (cfg, syntax) = VentoyConfig.parse(text)
        _config.update { it.copy(text = text, config = cfg ?: it.config, issues = syntax, dirty = true) }
        if (cfg != null) revalidate()
    }

    private fun revalidate() = viewModelScope.launch(Dispatchers.IO) {
        val cfg = _config.value.config ?: return@launch
        val issues = runCatching { withData(false) { vol, _, _ -> VentoyConfigStore(vol).validate(cfg) } }.getOrElse { emptyList() }
        _config.update { if (it.config == cfg) it.copy(issues = issues) else it }
    }

    fun saveConfig() = quick {
        val st = _config.value
        val backup = withData(true) { vol, _, _ -> VentoyConfigStore(vol).saveText(st.text) }
        loadConfig()
        "ventoy.json salvo" + (backup?.let { " (backup: $it)" } ?: "")
    }

    fun restoreConfigBackup(name: String) = quick {
        withData(true) { vol, _, _ -> VentoyConfigStore(vol).restore(name) }
        loadConfig(); "Backup $name restaurado (a versão anterior também virou backup)"
    }

    fun deleteConfigBackup(name: String) = quick {
        withData(true) { vol, _, _ -> VentoyConfigStore(vol).deleteBackup(name) }
        loadConfig(); "Backup excluído"
    }

    // ================================================================= temas

    fun loadThemes() {
        _themes.update { it.copy(loading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { withData(false) { vol, _, _ -> ThemeManager(vol, VentoyConfigStore(vol)).discover() } }
                .onSuccess { t -> _themes.update { it.copy(themes = t, loading = false) } }
                .onFailure { e -> _themes.update { it.copy(loading = false) }; say("Erro ao listar temas: ${e.message}") }
        }
    }

    /** Carrega o tema e as imagens necessárias para a pré-visualização aproximada. */
    fun preview(theme: ThemeInfo) = quick {
        val p = withData(false) { vol, _, _ ->
            val tm = ThemeManager(vol, VentoyConfigStore(vol))
            val gt = tm.parse(theme.themeFile)
            val wanted = mutableSetOf<String>()
            gt.properties["desktop-image"]?.let { wanted += it }
            gt.find("boot_menu")?.properties?.let { bm ->
                listOf("menu_pixmap_style", "selected_item_pixmap_style").mapNotNull { bm[it] }.forEach { wanted += it.replace("*", "c") }
            }
            val images = wanted.mapNotNull { rel ->
                val path = theme.folder + "/" + rel
                runCatching { rel to tm.readImage(path) }.getOrNull()
            }.toMap()
            val items = runCatching {
                ImageCatalog.scan(vol, ImageCatalog.rulesFrom(VentoyConfigStore(vol).load().first, vol)).filter { it.visible }.map { it.name }
            }.getOrDefault(emptyList()).ifEmpty { listOf("(nenhuma imagem no pendrive)") }
            ThemePreview(theme.themeFile, gt, images, items.take(8))
        }
        _themes.update { it.copy(preview = p) }
        null
    }

    fun closePreview() = _themes.update { it.copy(preview = null) }

    fun installOriginalTheme() = quick {
        val path = withData(true) { vol, _, _ -> ThemeManager(vol, VentoyConfigStore(vol)).installOriginal(embedded, overwrite = true) }
        loadThemes(); "Tema original copiado para $path"
    }

    fun importTheme(uri: Uri, name: String) = longOp("Importar tema \"$name\"") { _, obs ->
        obs.step("Extraindo e copiando arquivos", 0, 1)
        val resolver = getApplication<Application>().contentResolver
        val path = withData(true) { vol, _, _ ->
            resolver.openInputStream(uri)?.use { ThemeManager(vol, VentoyConfigStore(vol)).importZip(it, name) } ?: throw IOException("Não foi possível abrir o .zip")
        }
        "Tema importado: $path"
    }

    fun applyThemes(files: List<String>, random: String?) = quick {
        withData(true) { vol, _, _ -> ThemeManager(vol, VentoyConfigStore(vol)).apply(files, defaultIndex = if (files.size > 1) 0 else 1, random = random) }
        loadThemes(); if (_config.value.loaded) loadConfig()
        "Tema aplicado no ventoy.json (backup automático criado)"
    }

    fun restoreDefaultTheme() = quick {
        withData(true) { vol, _, _ -> ThemeManager(vol, VentoyConfigStore(vol)).restoreDefault() }
        loadThemes(); "Tema padrão do Ventoy restaurado"
    }

    fun removeTheme(theme: ThemeInfo) = quick {
        withData(true) { vol, _, _ -> ThemeManager(vol, VentoyConfigStore(vol)).remove(theme) }
        loadThemes(); "Tema removido"
    }

    fun putThemeImage(theme: ThemeInfo, uri: Uri, fileName: String) = quick {
        val size = size(uri) ?: throw IOException("Tamanho desconhecido")
        val resolver = getApplication<Application>().contentResolver
        withData(true) { vol, _, _ ->
            resolver.openInputStream(uri)?.use { ThemeManager(vol, VentoyConfigStore(vol)).putImage(theme, fileName, it, size) }
        }
        loadThemes(); "Imagem $fileName gravada no tema"
    }

    // ================================================================= ferramentas

    fun runCheck() = longOp("Verificar instalação do Ventoy") { _, obs ->
        val h = ready ?: throw IOException("Nenhum pendrive")
        obs.step("Conferindo partições, carregador, VTOYEFI e exFAT", 0, 1)
        val report = session.withDevice(h.info.deviceName) { dev -> VentoyMaintenance.checkInstallation(dev, embedded) }
        _check.value = report
        val errors = report.count { it.level == CheckItem.Level.ERROR }
        val warnings = report.count { it.level == CheckItem.Level.WARNING }
        if (errors == 0) "Instalação OK (${warnings} aviso(s))" else "$errors problema(s) encontrado(s) — veja o relatório"
    }

    fun backupTo(uri: Uri, includeLarge: Boolean) = longOp("Backup da pasta /ventoy") { _, obs ->
        obs.step("Compactando /ventoy", 0, 1)
        val resolver = getApplication<Application>().contentResolver
        val n = withData(false) { vol, _, _ ->
            resolver.openOutputStream(uri)?.use { VentoyMaintenance.backup(vol, it, includeLarge) } ?: throw IOException("Não foi possível criar o arquivo")
        }
        "Backup concluído: $n arquivo(s)"
    }

    fun previewBackup(uri: Uri, onResult: (List<Pair<String, Long>>) -> Unit) = quick {
        val list = getApplication<Application>().contentResolver.openInputStream(uri)?.use { VentoyMaintenance.listBackup(it) }
            ?: throw IOException("Não foi possível abrir")
        onResult(list); null
    }

    fun restoreFrom(uri: Uri, overwrite: Boolean) = longOp("Restaurar backup") { _, obs ->
        obs.step("Gravando arquivos em /ventoy", 0, 1)
        val resolver = getApplication<Application>().contentResolver
        val restored = withData(true) { vol, _, _ ->
            resolver.openInputStream(uri)?.use { VentoyMaintenance.restoreBackup(vol, it, overwrite) } ?: throw IOException("Não foi possível abrir")
        }
        "${restored.size} arquivo(s) restaurado(s)"
    }

    fun createPersistence(req: VentoyPersistence.Request) = longOp("Criar persistência ${req.path.substringAfterLast('/')}") { cancel, obs ->
        obs.step(if (req.zeroFill) "Reservando e zerando ${req.sizeMiB} MiB" else "Reservando ${req.sizeMiB} MiB", 0, 2)
        val r = withData(true) { vol, data, _ ->
            VentoyPersistence.create(data, vol, VentoyConfigStore(vol), req, { obs.onProgress(it) }, cancel)
        }
        "Persistência criada: ${req.path} (${req.type}, rótulo ${req.label}, ${r.blocks} blocos)" + (req.linkImage?.let { " vinculada a $it" } ?: "")
    }

    fun setSecureBoot(enable: Boolean, pkg: VentoyPackage = embedded) {
        val h = ready ?: return
        longOp(if (enable) "Ligar Secure Boot" else "Desligar Secure Boot") { cancel, obs ->
            val r = session.withDevice(h.info.deviceName) { dev -> VentoyUpdater(pkg).update(dev, dev.sizeBytes, enable, obs, cancel) }
            "Secure Boot ${if (r.detected.secureBootSupport == true) "LIGADO" else "DESLIGADO"} (Ventoy ${r.toVersion}). Arquivos preservados."
        }
    }

    // ================================================================= atualizações

    fun refreshDownloaded() = viewModelScope.launch(Dispatchers.IO) {
        _updates.update { it.copy(downloaded = runCatching { AppGraph.releases.downloaded(AppGraph.downloadsDir) }.getOrDefault(emptyList())) }
    }

    fun loadReleases() {
        _updates.update { it.copy(loading = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { AppGraph.releases.listReleases() }
                .onSuccess { r -> _updates.update { it.copy(loading = false, releases = r) } }
                .onFailure { e -> _updates.update { it.copy(loading = false, error = "Não foi possível consultar o GitHub oficial: ${e.message}") } }
        }
    }

    fun download(release: VentoyReleaseClient.Release) {
        if (!ops.run("Baixar Ventoy ${release.version}", "GitHub oficial", null) { cancel, obs ->
                obs.step("Baixando ${release.linuxPackage?.name}", 0, 1)
                val d = AppGraph.releases.download(release, AppGraph.downloadsDir, { obs.onProgress(it) }, cancel)
                refreshDownloaded()
                if (d.verified) "Ventoy ${d.version} baixado e conferido com o sha256.txt oficial"
                else "Ventoy ${d.version} baixado SEM checksum oficial — autenticidade não verificada"
            }) say("Aguarde a operação em andamento terminar")
    }

    fun updateWith(pkg: VentoyPackage) {
        val h = ready ?: return
        longOp("Atualizar Ventoy para ${runCatching { pkg.version }.getOrDefault("?")}") { cancel, obs ->
            val r = session.withDevice(h.info.deviceName) { dev -> VentoyUpdater(pkg).update(dev, dev.sizeBytes, null, obs, cancel) }
            "Ventoy ${r.fromVersion ?: "?"} → ${r.toVersion} (pacote ${pkg.origin}). Arquivos e ISOs preservados."
        }
    }

    // ================================================================= utilitários

    private fun displayName(uri: Uri): String? = getApplication<Application>().contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun size(uri: Uri): Long? = getApplication<Application>().contentResolver
        .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

    companion object {
        fun child(dir: String, name: String) = dir.trimEnd('/') + "/" + name

        /** Tipo amigável pelo nome do arquivo. */
        fun typeLabel(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "iso" -> "Imagem ISO"
            "img" -> "Imagem de disco"
            "wim", "esd" -> "Imagem Windows (WIM)"
            "vhd", "vhdx" -> "Disco virtual"
            "efi" -> "Executável EFI"
            "vtoy" -> "Imagem Ventoy"
            "dat" -> "Persistência / dados"
            "json", "cfg", "txt", "conf", "xml" -> "Configuração / texto"
            "png", "jpg", "jpeg", "tga" -> "Imagem (tema)"
            "zip", "7z", "tar", "gz", "xz" -> "Compactado"
            "" -> "Sem extensão"
            else -> name.substringAfterLast('.').uppercase()
        }
    }
}

/** Atalho para inputStream de ByteArray. */
private fun ByteArray.inputStream(): InputStream = java.io.ByteArrayInputStream(this)
