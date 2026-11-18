package dev.bootforge.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.bootforge.app.data.AppGraph
import dev.bootforge.app.data.Format
import dev.bootforge.app.data.UriRandomReader
import dev.bootforge.blockdev.ProgressListener
import dev.bootforge.blockdev.ProgressTracker
import dev.bootforge.isoedit.BuildPlan
import dev.bootforge.isoedit.EditSession
import dev.bootforge.isoedit.ExternalContent
import dev.bootforge.isoedit.ExtractSink
import dev.bootforge.isoedit.IsoBuilder
import dev.bootforge.isoedit.IsoExtractor
import dev.bootforge.isoedit.IsoSource
import dev.bootforge.isoedit.IsoValidator
import dev.bootforge.isoedit.Paths
import dev.bootforge.isoedit.PendingChange
import dev.bootforge.isoedit.RebuildMode
import dev.bootforge.isoedit.Source
import dev.bootforge.isoedit.ValidationReport
import dev.bootforge.isoedit.VDir
import dev.bootforge.isoedit.VFile
import dev.bootforge.isoedit.VNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Item da lista do explorador. */
data class EditorItem(
    val name: String, val path: String, val isDir: Boolean, val size: Long,
    /** Conteúdo novo ou alterado nesta sessão. */
    val isNew: Boolean, val isBoot: Boolean, val isLink: Boolean, val children: Int,
)

enum class EditorSort(val label: String) { NAME("Nome"), SIZE("Tamanho"), TYPE("Tipo") }

/** Resumo da imagem aberta. */
data class SourceSummary(
    val name: String, val size: Long, val format: String, val volumeId: String,
    val files: Int, val dirs: Int, val boots: List<String>, val hybrid: Boolean,
    val modes: Map<RebuildMode, String?>, val recommended: RebuildMode, val warnings: List<String>, val blockers: List<String>,
)

sealed interface ViewerState {
    val path: String
    val size: Long
    data class Text(override val path: String, val text: String, override val size: Long) : ViewerState
    data class Hex(override val path: String, val dump: String, override val size: Long) : ViewerState
}

/** Prévia antes de gerar a ISO. */
data class SavePreview(val mode: RebuildMode, val totalBytes: Long, val warnings: List<String>, val bootSummary: String, val wasted: Long, val volumeId: String)

data class SaveReport(val name: String, val sha256: String, val report: ValidationReport, val mode: RebuildMode)

data class EditorUi(
    val loading: String? = null,
    val error: String? = null,
    val summary: SourceSummary? = null,
    val cwd: String = "/",
    val items: List<EditorItem> = emptyList(),
    val sort: EditorSort = EditorSort.NAME,
    val query: String = "",
    val searchResults: List<EditorItem>? = null,
    val selection: Set<String> = emptySet(),
    val history: List<String> = emptyList(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val pending: List<PendingChange> = emptyList(),
    val viewer: ViewerState? = null,
    val preview: SavePreview? = null,
    val saved: SaveReport? = null,
    val message: String? = null,
)

/**
 * Etapa 5 — Editor de ISO. A imagem é lida direto do arquivo do celular (sem cópia) e todas
 * as alterações ficam na [EditSession] até o usuário gerar a nova ISO.
 */
class IsoEditorViewModel(app: Application) : AndroidViewModel(app) {

    private val ops = AppGraph.operations
    private val _ui = MutableStateFlow(EditorUi())
    val ui: StateFlow<EditorUi> = _ui.asStateFlow()

    private var reader: UriRandomReader? = null
    private var session: EditSession? = null
    private var sourceUri: Uri? = null
    private var plan: BuildPlan? = null
    private var workDir: File? = null

    private val resolver get() = getApplication<Application>().contentResolver

    // ------------------------------------------------------------------ abrir / fechar

    fun open(uri: Uri) {
        close()
        val name = displayName(uri) ?: "imagem.iso"
        _ui.value = EditorUi(loading = "Abrindo $name…")
        runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val r = UriRandomReader.open(getApplication(), uri)
                val src = IsoSource.open(r)
                val dir = File(getApplication<Application>().cacheDir, "iso-editor/${System.currentTimeMillis()}")
                reader = r; sourceUri = uri; workDir = dir
                session = EditSession(src, dir)
                val summary = SourceSummary(
                    name, r.size, src.formatLabel, src.volumeId, Paths.countFiles(src.root), Paths.countDirs(src.root),
                    src.bootImages.map { "${it.entry.platformName}: ${it.path ?: "imagem oculta"} (${Format.bytes(it.size)})" },
                    src.isHybrid, IsoBuilder.availability(src), IsoBuilder.recommended(src), src.warnings, src.blockers,
                )
                _ui.value = EditorUi(summary = summary)
                refresh()
            } catch (e: Exception) {
                close()
                _ui.value = EditorUi(error = "Não foi possível abrir $name: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Fecha a imagem e descarta a área de trabalho (as alterações não salvas são perdidas). */
    fun close() {
        runCatching { reader?.close() }
        workDir?.deleteRecursively()
        reader = null; session = null; sourceUri = null; plan = null; workDir = null
        _ui.value = EditorUi()
    }

    override fun onCleared() { close() }

    // ------------------------------------------------------------------ navegação

    fun navigate(path: String) { _ui.update { it.copy(cwd = path, selection = emptySet(), query = "", searchResults = null) }; refresh() }
    fun up() { val c = _ui.value.cwd; if (c != "/") navigate(Paths.parent(c)) }
    fun setSort(s: EditorSort) { _ui.update { it.copy(sort = s) }; refresh() }
    fun toggleSelection(path: String) = _ui.update { s -> s.copy(selection = if (path in s.selection) s.selection - path else s.selection + path) }
    fun selectAll() = _ui.update { s -> s.copy(selection = (s.searchResults ?: s.items).map { it.path }.toSet()) }
    fun clearSelection() = _ui.update { it.copy(selection = emptySet()) }
    fun consumeMessage() = _ui.update { it.copy(message = null) }

    fun search(query: String) {
        val s = session ?: return
        if (query.isBlank()) { _ui.update { it.copy(query = "", searchResults = null) }; return }
        val q = query.trim().lowercase()
        val out = mutableListOf<EditorItem>()
        Paths.walk(s.root) { p, n -> if (n.name.lowercase().contains(q) && out.size < 500) out += item(p, n) }
        _ui.update { it.copy(query = query, searchResults = out) }
    }

    private fun refresh() {
        val s = session ?: return
        val st = _ui.value
        val dir = s.find(st.cwd) as? VDir ?: s.root.also { _ui.update { u -> u.copy(cwd = "/") } }
        val items = dir.children.map { item(Paths.join(_ui.value.cwd, it.name), it) }
        val sorted = when (st.sort) {
            EditorSort.NAME -> items.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
            EditorSort.SIZE -> items.sortedWith(compareBy({ !it.isDir }, { -it.size }))
            EditorSort.TYPE -> items.sortedWith(compareBy({ !it.isDir }, { it.name.substringAfterLast('.', "").lowercase() }, { it.name.lowercase() }))
        }
        _ui.update {
            it.copy(
                items = sorted, history = s.history, canUndo = s.canUndo, canRedo = s.canRedo, pending = s.pendingChanges(),
                selection = it.selection.filter { p -> s.find(p) != null }.toSet(), saved = null,
            )
        }
        st.query.takeIf { it.isNotBlank() }?.let { search(it) }
    }

    private fun item(path: String, n: VNode): EditorItem {
        val boots = session?.source?.bootImages?.mapNotNull { it.path }?.toSet() ?: emptySet()
        return when (n) {
            is VDir -> EditorItem(n.name, path, true, 0, false, false, false, n.children.size)
            is VFile -> EditorItem(n.name, path, false, n.size, n.source is Source.External, path in boots, n.meta.isLink, 0)
        }
    }

    // ------------------------------------------------------------------ edição

    private fun edit(block: (EditSession) -> String?) {
        val s = session ?: return
        try {
            val msg = block(s)
            refresh()
            msg?.let { m -> _ui.update { it.copy(message = m) } }
        } catch (e: Exception) {
            _ui.update { it.copy(message = e.message ?: e.javaClass.simpleName) }
        }
    }

    fun mkdir(name: String) = edit { it.mkdir(_ui.value.cwd, name.trim()); null }
    fun rename(path: String, newName: String) = edit { it.rename(path, newName.trim()); null }
    fun delete(paths: Collection<String>) = edit { s -> paths.forEach { s.delete(it) }; "${paths.size} item(ns) excluído(s) da edição" }
    fun move(paths: Collection<String>, dest: String) = edit { s -> paths.forEach { s.move(it, dest) }; "${paths.size} item(ns) movido(s) para $dest" }
    fun copy(paths: Collection<String>, dest: String) = edit { s -> paths.forEach { s.copy(it, dest) }; "${paths.size} item(ns) copiado(s) para $dest" }
    fun undo() = edit { "Desfeito: ${it.undo() ?: "nada"}" }
    fun redo() = edit { "Refeito: ${it.redo() ?: "nada"}" }
    fun discardAll() = edit { it.discardAll(); "Todas as alterações foram descartadas" }

    fun addFiles(uris: List<Uri>) = edit { s ->
        var replaced = 0
        for (u in uris) {
            runCatching { resolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val c = UriContent(u)
            val exists = (s.find(_ui.value.cwd) as? VDir)?.child(c.label) != null
            if (exists) replaced++
            s.addFile(_ui.value.cwd, c.label, c, overwrite = true)
        }
        "${uris.size} arquivo(s) adicionado(s)" + if (replaced > 0) " ($replaced substituído(s))" else ""
    }

    fun addFolder(tree: Uri) {
        val s = session ?: return
        val cwd = _ui.value.cwd
        _ui.update { it.copy(loading = "Lendo a pasta…") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val root = DocumentFile.fromTreeUri(getApplication(), tree) ?: throw IOException("Pasta inválida")
                val name = root.name ?: "pasta"
                val items = mutableListOf<Pair<String, ExternalContent?>>()
                fun walk(d: DocumentFile, rel: String) {
                    items += rel to null
                    for (c in d.listFiles()) {
                        val r = "$rel/${c.name}"
                        if (c.isDirectory) walk(c, r) else items += r to UriContent(c.uri, c.name, c.length())
                    }
                }
                walk(root, name)
                s.addTree(cwd, items, "pasta \"$name\"")
                _ui.update { it.copy(loading = null, message = "Pasta \"$name\" adicionada (${items.count { it.second != null }} arquivos)") }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = null, message = e.message ?: e.javaClass.simpleName) }
            }
            refresh()
        }
    }

    fun replaceFile(path: String, uri: Uri) = edit { it.replace(path, UriContent(uri)); "Arquivo substituído: ${Paths.name(path)}" }

    // ------------------------------------------------------------------ visualizar / editar texto

    fun openViewer(path: String) {
        val s = session ?: return
        val f = s.find(path) as? VFile ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val max = 2L shl 20
                val want = minOf(f.size, max).toInt()
                val bytes = ByteArray(want)
                s.open(f).use { input -> var got = 0; while (got < want) { val n = input.read(bytes, got, want - got); if (n < 0) break; got += n } }
                val isText = f.size <= max && bytes.take(8192).none { it == 0.toByte() }
                val v = if (isText) ViewerState.Text(path, String(bytes, Charsets.UTF_8), f.size) else ViewerState.Hex(path, hexDump(bytes, 64 * 1024), f.size)
                _ui.update { it.copy(viewer = v) }
            } catch (e: Exception) {
                _ui.update { it.copy(message = "Não foi possível abrir: ${e.message}") }
            }
        }
    }

    fun closeViewer() = _ui.update { it.copy(viewer = null) }

    fun saveText(path: String, text: String) { edit { it.replaceText(path, text); "Texto salvo na edição: ${Paths.name(path)}" }; closeViewer() }

    fun properties(path: String): List<Pair<String, String>> {
        val s = session ?: return emptyList()
        val n = s.find(path) ?: return emptyList()
        val out = mutableListOf("Caminho" to path, "Tipo" to if (n is VDir) "Pasta" else "Arquivo")
        when (n) {
            is VDir -> { out += "Itens" to n.children.size.toString(); out += "Arquivos (total)" to Paths.countFiles(n).toString(); out += "Tamanho (total)" to Format.bytes(Paths.totalBytes(n)) }
            is VFile -> {
                out += "Tamanho" to "${Format.bytes(n.size)} (${n.size} bytes)"
                when (val src = n.source) {
                    is Source.Original -> { out += "Origem" to "Imagem original"; out += "Setor (LBA)" to src.extents.joinToString { (it.offset / 2048).toString() } }
                    is Source.External -> out += "Origem" to "Novo nesta edição: ${s.external(src.key).label}"
                }
                n.meta.isoName?.let { out += "Nome ISO9660" to it }
                if (n.meta.isLink) out += "Rock Ridge" to "link simbólico"
                s.source.bootImages.filter { it.path == path }.forEach { out += "Inicialização" to "Imagem de boot ${it.entry.platformName} (${it.entry.mediaName})" }
            }
        }
        if (n.meta.susp != null) out += "Rock Ridge" to n.meta.susp!!.joinToString(" ") { it.sig }
        return out
    }

    // ------------------------------------------------------------------ gerar nova ISO

    fun prepareSave(mode: RebuildMode, volumeId: String?) {
        val s = session ?: return
        _ui.update { it.copy(loading = "Planejando a nova ISO…") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val p = IsoBuilder.plan(s, mode, volumeId?.trim()?.takeIf { it.isNotEmpty() })
                plan = p
                _ui.update { it.copy(loading = null, preview = SavePreview(mode, p.totalBytes, p.warnings, p.bootSummary, p.wastedBytes, volumeId ?: s.source.volumeId)) }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = null, message = "Não é possível gerar: ${e.message}") }
            }
        }
    }

    fun cancelPreview() { plan = null; _ui.update { it.copy(preview = null) } }

    /** Grava a nova ISO no arquivo escolhido e valida relendo-o. A imagem original não é tocada. */
    fun save(target: Uri) {
        val s = session ?: return
        val p = plan ?: return
        val name = displayName(target) ?: "nova.iso"
        _ui.update { it.copy(preview = null) }
        ops.run("Gerar ISO: $name", "Editor de ISO", null) { cancel, obs ->
            obs.step("Gerando a nova ISO (${p.mode.title})", 0, 2)
            obs.onLog("Tamanho final: ${Format.bytes(p.totalBytes)}")
            p.warnings.forEach { obs.onLog("Aviso: $it") }
            val sha = resolver.openOutputStream(target, "wt")?.buffered(1 shl 20)?.use { out -> p.write(out, { obs.onProgress(it) }, cancel) }
                ?: throw IOException("Não foi possível gravar o arquivo de destino")
            obs.onLog("SHA-256: $sha")
            obs.step("Validando (relendo a ISO gerada)", 1, 2)
            val report = UriRandomReader.open(getApplication(), target).use { r -> IsoValidator.validate(p, s, r, sha, true, { obs.onProgress(it) }, cancel) }
            report.failures.forEach { obs.onLog("FALHA: ${it.title} — ${it.detail}") }
            _ui.update { it.copy(saved = SaveReport(name, sha, report, p.mode)) }
            if (!report.ok) throw IOException("A validação encontrou ${report.failures.size} problema(s). A ISO gerada NÃO deve ser usada.")
            "\"$name\" gerada e validada: ${report.filesChecked} arquivos conferidos. ${report.boot.label}."
        }
    }

    fun dismissSaved() = _ui.update { it.copy(saved = null) }

    /** Exclui a ISO ORIGINAL (só depois de gerar e validar a nova, e com confirmação na tela). */
    fun deleteOriginal() {
        val uri = sourceUri ?: return
        val ok = runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
        if (ok) close()
        _ui.update { it.copy(message = if (ok) "ISO original excluída. Use a nova ISO gerada." else "O local da ISO original não permite excluir pelo app; exclua pelo gerenciador de arquivos.") }
    }

    // ------------------------------------------------------------------ extrair e copiar

    fun extract(paths: List<String>, tree: Uri) {
        val s = session ?: return
        ops.run("Extrair da ISO", "Editor de ISO", null) { cancel, obs ->
            val root = DocumentFile.fromTreeUri(getApplication(), tree) ?: throw IOException("Pasta de destino inválida")
            obs.step("Extraindo ${if (paths.isEmpty()) "todo o conteúdo" else "${paths.size} item(ns)"}", 0, 1)
            val dirs = HashMap<String, DocumentFile>()
            fun dirFor(rel: String): DocumentFile {
                val parts = Paths.split(rel)
                var cur = root; var acc = ""
                for (p in parts) {
                    acc += "/$p"
                    cur = dirs.getOrPut(acc) { cur.findFile(p)?.takeIf { it.isDirectory } ?: cur.createDirectory(p) ?: throw IOException("Não foi possível criar $p") }
                }
                return cur
            }
            val res = IsoExtractor.extract(s, paths, object : ExtractSink {
                override fun directory(path: String) { dirFor(path) }
                override fun file(path: String, size: Long, data: InputStream) {
                    val d = dirFor(Paths.parent(path))
                    d.findFile(Paths.name(path))?.delete()
                    val f = d.createFile("application/octet-stream", Paths.name(path)) ?: throw IOException("Não foi possível criar ${Paths.name(path)}")
                    resolver.openOutputStream(f.uri)?.use { data.copyTo(it, 1 shl 20) } ?: throw IOException("Não foi possível gravar ${Paths.name(path)}")
                }
            }, { obs.onProgress(it) }, cancel)
            "${res.files} arquivo(s) e ${res.dirs} pasta(s) extraídos (${Format.bytes(res.bytes)})."
        }
    }

    /** Cópia de segurança da ISO original, byte a byte, com SHA-256. */
    fun copyOriginal(target: Uri) {
        val r = reader ?: return
        ops.run("Copiar ISO original", "Editor de ISO", null) { cancel, obs ->
            obs.step("Copiando", 0, 1)
            val tracker = ProgressTracker("Copiando", r.size, ProgressListener { obs.onProgress(it) })
            val md = java.security.MessageDigest.getInstance("SHA-256")
            resolver.openOutputStream(target, "wt")?.use { out ->
                val buf = ByteArray(1 shl 20); var pos = 0L
                while (pos < r.size) {
                    cancel.throwIfCancelled()
                    val n = r.read(pos, buf, 0, minOf(buf.size.toLong(), r.size - pos).toInt())
                    if (n <= 0) throw IOException("Leitura interrompida")
                    out.write(buf, 0, n); md.update(buf, 0, n); pos += n; tracker.update(pos)
                }
            } ?: throw IOException("Não foi possível gravar a cópia")
            "Cópia criada (${Format.bytes(r.size)}). SHA-256: ${md.digest().joinToString("") { "%02x".format(it) }}"
        }
    }

    /** Todas as pastas da árvore atual (para escolher destino de mover/copiar). */
    fun dirPaths(): List<String> {
        val s = session ?: return emptyList()
        val out = mutableListOf<String>()
        Paths.walk(s.root) { p, n -> if (n is VDir) out += p }
        return out
    }

    // ------------------------------------------------------------------ auxiliares

    private fun displayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun hexDump(b: ByteArray, max: Int): String = buildString {
        val n = minOf(b.size, max)
        var i = 0
        while (i < n) {
            append("%08X  ".format(i))
            for (j in 0 until 16) append(if (i + j < n) "%02X ".format(b[i + j]) else "   ")
            append(" ")
            for (j in 0 until 16) if (i + j < n) { val c = b[i + j].toInt() and 0xFF; append(if (c in 32..126) c.toChar() else '.') }
            append('\n'); i += 16
        }
        if (b.size > max) append("… (mostrando os primeiros ${max / 1024} KB)")
    }

    /** Arquivo escolhido no celular (lido só na hora de gerar a ISO). */
    private inner class UriContent(private val uri: Uri, name: String? = null, size: Long? = null) : ExternalContent {
        override val label: String = name ?: displayName(uri) ?: "arquivo"
        override val size: Long = size ?: runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
        }.getOrNull() ?: throw IOException("Não foi possível obter o tamanho de $label")
        override fun open(): InputStream = resolver.openInputStream(uri) ?: throw IOException("Não foi possível abrir $label")
    }
}
