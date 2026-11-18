package dev.bootforge.isoedit

import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong

/** Alteração pendente, comparando a árvore atual com a original. */
data class PendingChange(val kind: Kind, val path: String, val detail: String = "") {
    enum class Kind(val label: String) { ADDED("Adicionado"), MODIFIED("Modificado"), REMOVED("Removido") }
}

/**
 * Sessão de edição NÃO destrutiva sobre uma ISO.
 *
 *  - A imagem original nunca é alterada: as operações só mudam a árvore em memória.
 *  - Cada operação guarda a nova raiz no histórico → desfazer/refazer ilimitados.
 *  - Conteúdo novo (arquivos adicionados, textos editados) fica registrado como
 *    [ExternalContent]; textos editados vão para a [workDir] (área temporária de trabalho).
 */
class EditSession(val source: IsoSource, private val workDir: File? = null) {

    /** Uma operação já aplicada: descrição para o usuário e a árvore resultante. */
    class Step(val description: String, val root: VDir)

    private val steps = mutableListOf<Step>()
    private var applied = 0
    private val externals = HashMap<String, ExternalContent>()
    private val counter = AtomicLong()

    val root: VDir get() = if (applied == 0) source.root else steps[applied - 1].root
    val history: List<String> get() = steps.take(applied).map { it.description }
    val redoable: List<String> get() = steps.drop(applied).map { it.description }
    val canUndo get() = applied > 0
    val canRedo get() = applied < steps.size
    val isModified get() = applied > 0

    fun undo(): String? = if (applied > 0) steps[--applied].description else null
    fun redo(): String? = if (applied < steps.size) steps[applied++].description else null

    /** Cancela todas as alterações (volta à imagem original). */
    fun discardAll() { steps.clear(); applied = 0 }

    fun external(key: String): ExternalContent = externals[key] ?: throw IllegalStateException("Conteúdo da sessão não encontrado: $key")

    fun find(path: String): VNode? = if (Paths.split(path).isEmpty()) root else Paths.find(root, path)

    /** Abre o conteúdo atual de um arquivo (original ou novo). */
    fun open(file: VFile): InputStream = when (val s = file.source) {
        is Source.Original -> source.openOriginal(s, file.size)
        is Source.External -> external(s.key).open()
    }

    // ------------------------------------------------------------------ operações

    fun mkdir(parent: String, name: String) {
        checkName(name)
        val dir = dirAt(parent)
        if (dir.child(name) != null) throw IllegalArgumentException("Já existe \"$name\" em $parent")
        apply("Criar pasta ${Paths.join(parent, name)}", Paths.replace(root, Paths.join(parent, name), VDir(name)))
    }

    /** Adiciona um arquivo (ou substitui, se [overwrite]). */
    fun addFile(parent: String, name: String, content: ExternalContent, overwrite: Boolean = false) {
        checkName(name)
        val dir = dirAt(parent)
        val existing = dir.child(name)
        if (existing is VDir) throw IllegalArgumentException("Já existe uma pasta chamada \"$name\"")
        if (existing != null && !overwrite) throw IllegalArgumentException("Já existe \"$name\" em $parent")
        val key = register(content)
        val path = Paths.join(parent, existing?.name ?: name)
        val node = VFile(existing?.name ?: name, content.size, Source.External(key), existing?.meta?.let(::contentChanged) ?: Meta())
        apply((if (existing != null) "Substituir " else "Adicionar ") + "$path (${content.label})", Paths.replace(root, path, node))
    }

    /** Adiciona vários itens de uma vez (ex.: pasta inteira do celular) como UMA operação. */
    fun addTree(parent: String, items: List<Pair<String, ExternalContent?>>, label: String) {
        var r = root
        for ((rel, content) in items) {
            val parts = Paths.split(rel)
            parts.forEach(::checkName)
            var cur = parent
            for (p in parts.dropLast(1)) {
                val next = Paths.join(cur, p)
                when (Paths.find(r, next)) {
                    null -> r = Paths.replace(r, next, VDir(p))
                    is VFile -> throw IllegalArgumentException("Conflito: $next é um arquivo")
                    else -> Unit
                }
                cur = next
            }
            val path = Paths.join(cur, parts.last())
            val existing = Paths.find(r, path)
            r = if (content == null) {
                if (existing is VDir) r else if (existing != null) throw IllegalArgumentException("Conflito: $path já existe") else Paths.replace(r, path, VDir(parts.last()))
            } else {
                if (existing is VDir) throw IllegalArgumentException("Conflito: $path é uma pasta")
                Paths.replace(r, path, VFile(parts.last(), content.size, Source.External(register(content)), existing?.meta?.let(::contentChanged) ?: Meta()))
            }
        }
        apply("Adicionar $label em $parent (${items.count { it.second != null }} arquivos)", r)
    }

    fun replace(path: String, content: ExternalContent) {
        val f = Paths.find(root, path) as? VFile ?: throw IllegalArgumentException("Arquivo não encontrado: $path")
        val node = f.copy(size = content.size, source = Source.External(register(content)), meta = contentChanged(f.meta))
        apply("Substituir $path (${content.label})", Paths.replace(root, path, node))
    }

    /** Salva um texto editado (vai para a área de trabalho, sem tocar a ISO). */
    fun replaceText(path: String, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val content = workDir?.let { dir ->
            dir.mkdirs()
            val f = File(dir, "texto-${counter.incrementAndGet()}.txt").apply { writeBytes(bytes) }
            FileContent(f, "texto editado")
        } ?: BytesContent(bytes, "texto editado")
        replace(path, content)
    }

    fun rename(path: String, newName: String) {
        checkName(newName)
        val node = Paths.find(root, path) ?: throw IllegalArgumentException("Não encontrado: $path")
        val parent = dirAt(Paths.parent(path))
        val clash = parent.child(newName)
        if (clash != null && clash !== node) throw IllegalArgumentException("Já existe \"$newName\" nesta pasta")
        val renamed = when (node) { is VFile -> node.copy(name = newName, meta = node.meta.copy(isoName = null)); is VDir -> node.copy(name = newName, meta = node.meta.copy(isoName = null)) }
        val r = Paths.replace(Paths.replace(root, path, null), Paths.join(Paths.parent(path), newName), renamed)
        apply("Renomear $path → $newName", r)
    }

    fun delete(path: String) {
        val node = Paths.find(root, path) ?: throw IllegalArgumentException("Não encontrado: $path")
        val what = if (node is VDir) "pasta" else "arquivo"
        apply("Excluir $what $path", Paths.replace(root, path, null))
    }

    fun move(path: String, newParent: String) {
        val node = Paths.find(root, path) ?: throw IllegalArgumentException("Não encontrado: $path")
        if (node is VDir && (newParent == path || newParent.startsWith(path.trimEnd('/') + "/"))) throw IllegalArgumentException("Não é possível mover uma pasta para dentro dela mesma")
        val target = dirAt(newParent)
        if (target.child(node.name) != null) throw IllegalArgumentException("Já existe \"${node.name}\" em $newParent")
        val r = Paths.replace(Paths.replace(root, path, null), Paths.join(newParent, node.name), node)
        apply("Mover $path → $newParent", r)
    }

    /** Copia dentro da própria imagem (os dados originais são reaproveitados, não duplicados na edição). */
    fun copy(path: String, newParent: String, newName: String? = null) {
        val node = Paths.find(root, path) ?: throw IllegalArgumentException("Não encontrado: $path")
        val name = newName ?: node.name
        checkName(name)
        if (dirAt(newParent).child(name) != null) throw IllegalArgumentException("Já existe \"$name\" em $newParent")
        val copy = when (node) { is VFile -> node.copy(name = name, meta = node.meta.copy(isoName = null)); is VDir -> node.copy(name = name, meta = node.meta.copy(isoName = null)) }
        apply("Copiar $path → ${Paths.join(newParent, name)}", Paths.replace(root, Paths.join(newParent, name), copy))
    }

    // ------------------------------------------------------------------ alterações pendentes

    /** Diferenças entre a árvore atual e a original (para revisar antes de salvar). */
    fun pendingChanges(): List<PendingChange> {
        val before = HashMap<String, VNode>()
        Paths.walk(source.root) { p, n -> before[p] = n }
        val out = mutableListOf<PendingChange>()
        val seen = HashSet<String>()
        Paths.walk(root) { p, n ->
            seen += p
            val old = before[p]
            when {
                old == null -> out += PendingChange(PendingChange.Kind.ADDED, p, if (n is VFile) sizeText(n.size) else "pasta")
                n is VFile && old is VFile && n.source != old.source -> out += PendingChange(PendingChange.Kind.MODIFIED, p, sizeText(old.size) + " → " + sizeText(n.size))
                (n is VDir) != (old is VDir) -> out += PendingChange(PendingChange.Kind.MODIFIED, p, "tipo alterado")
            }
        }
        before.keys.filter { it !in seen }.forEach { p -> if (Paths.parent(p) in seen || Paths.parent(p) == "/") out += PendingChange(PendingChange.Kind.REMOVED, p, if (before[p] is VDir) "pasta" else sizeText((before[p] as VFile).size)) }
        return out.sortedBy { it.path.lowercase() }
    }

    // ------------------------------------------------------------------ interno

    private fun apply(description: String, newRoot: VDir) {
        while (steps.size > applied) steps.removeAt(steps.lastIndex) // nova operação descarta o "refazer"
        steps += Step(description, newRoot)
        applied = steps.size
    }

    private fun register(content: ExternalContent): String {
        val key = "ext-${counter.incrementAndGet()}"
        externals[key] = content
        return key
    }

    private fun dirAt(path: String): VDir = (if (Paths.split(path).isEmpty()) root else Paths.find(root, path)) as? VDir
        ?: throw IllegalArgumentException("Pasta não encontrada: $path")

    /** Conteúdo novo: mantém permissões/datas Rock Ridge, mas descarta a compactação zisofs. */
    private fun contentChanged(m: Meta) = m.copy(susp = m.susp?.filter { it.sig != "ZF" })

    private fun sizeText(b: Long) = when {
        b >= 1L shl 30 -> String.format(java.util.Locale.ROOT, "%.2f GB", b / 1073741824.0)
        b >= 1L shl 20 -> String.format(java.util.Locale.ROOT, "%.1f MB", b / 1048576.0)
        b >= 1024 -> String.format(java.util.Locale.ROOT, "%.1f KB", b / 1024.0)
        else -> "$b bytes"
    }

    companion object {
        fun checkName(name: String) {
            require(name.isNotBlank()) { "Nome vazio" }
            require(name != "." && name != "..") { "Nome inválido" }
            require('/' !in name && '\u0000' !in name) { "O nome não pode conter \"/\"" }
            require(name.toByteArray(Charsets.UTF_8).size <= 255) { "Nome longo demais (máx. 255 bytes)" }
        }
    }
}

/** Conteúdo vindo de um arquivo local. */
class FileContent(private val file: File, override val label: String = file.name) : ExternalContent {
    override val size: Long = file.length()
    override fun open(): InputStream = file.inputStream()
}

/** Conteúdo em memória (textos pequenos). */
class BytesContent(private val bytes: ByteArray, override val label: String) : ExternalContent {
    override val size: Long get() = bytes.size.toLong()
    override fun open(): InputStream = bytes.inputStream()
}
