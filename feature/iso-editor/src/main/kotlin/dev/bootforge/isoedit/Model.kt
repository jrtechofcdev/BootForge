package dev.bootforge.isoedit

import dev.bootforge.iso.Extent

/*
 * Modelo da árvore em edição.
 *
 * É IMUTÁVEL: cada operação (renomear, excluir, adicionar…) gera uma nova raiz reaproveitando
 * os ramos que não mudaram. Isso torna desfazer/refazer trivial e seguro — basta guardar as
 * raízes anteriores — e garante que a imagem original nunca é tocada durante a edição.
 */

/** Item da árvore (arquivo ou pasta). */
sealed class VNode {
    abstract val name: String
    abstract val meta: Meta
}

/**
 * Metadados preservados do original (vazios em itens novos).
 * @property recDate data de gravação do registro ISO9660 (7 bytes), copiada como está
 * @property susp entradas Rock Ridge originais (permissões, datas, links…), sem NM/CE
 * @property isoName identificador ISO9660 original (ex.: "ISOLINUX.BIN;1"); mantido para
 *   que carregadores que procuram o nome curto continuem achando o arquivo
 * @property hidden flag "oculto" do ISO9660
 */
data class Meta(
    val recDate: ByteArray? = null,
    val susp: List<SuspEntry>? = null,
    val isoName: String? = null,
    val hidden: Boolean = false,
) {
    val isLink: Boolean get() = susp?.any { it.sig == "SL" } == true
}

data class VFile(override val name: String, val size: Long, val source: Source, override val meta: Meta = Meta()) : VNode()

data class VDir(override val name: String, val children: List<VNode> = emptyList(), override val meta: Meta = Meta()) : VNode() {
    fun child(name: String): VNode? = children.firstOrNull { it.name == name } ?: children.firstOrNull { it.name.equals(name, ignoreCase = true) }
}

/** De onde vêm os dados de um arquivo. */
sealed interface Source {
    /** Dados dentro da imagem original, ainda não modificados. */
    data class Original(val extents: List<Extent>) : Source

    /** Arquivo de fora (celular, área de trabalho, texto editado). [key] identifica o conteúdo na sessão. */
    data class External(val key: String) : Source
}

/** Conteúdo externo registrado na sessão (o modelo guarda só a chave). */
interface ExternalContent {
    val size: Long
    /** Descrição para o usuário (ex.: nome do arquivo de origem). */
    val label: String
    fun open(): java.io.InputStream
}

/** Funções de caminho ("/boot/grub/grub.cfg"). */
object Paths {
    fun split(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }
    fun join(parent: String, name: String): String = parent.trimEnd('/') + "/" + name
    fun parent(path: String): String = split(path).dropLast(1).joinToString("/", prefix = "/")
    fun name(path: String): String = split(path).lastOrNull() ?: "/"

    fun find(root: VDir, path: String): VNode? {
        var cur: VNode = root
        for (p in split(path)) cur = (cur as? VDir)?.child(p) ?: return null
        return cur
    }

    /** Percorre a árvore (pastas antes do conteúdo). */
    fun walk(dir: VDir, path: String = "/", visit: (String, VNode) -> Unit) {
        for (c in dir.children) {
            val p = join(path, c.name)
            visit(p, c)
            if (c is VDir) walk(c, p, visit)
        }
    }

    /** Substitui o item em [path] (null = remove), copiando só o caminho até ele. */
    fun replace(root: VDir, path: String, newNode: VNode?): VDir {
        val parts = split(path)
        require(parts.isNotEmpty()) { "Não é possível substituir a raiz" }
        fun rec(dir: VDir, i: Int): VDir {
            val name = parts[i]
            val idx = dir.children.indexOfFirst { it.name == name }.takeIf { it >= 0 }
                ?: dir.children.indexOfFirst { it.name.equals(name, ignoreCase = true) }
            if (i == parts.lastIndex) {
                val list = dir.children.toMutableList()
                when {
                    idx < 0 && newNode == null -> throw IllegalArgumentException("Não encontrado: $path")
                    idx < 0 -> list += newNode!!
                    newNode == null -> list.removeAt(idx)
                    else -> list[idx] = newNode
                }
                return dir.copy(children = list)
            }
            if (idx < 0) throw IllegalArgumentException("Pasta não encontrada: ${parts.take(i + 1).joinToString("/", "/")}")
            val sub = dir.children[idx] as? VDir ?: throw IllegalArgumentException("Não é uma pasta: ${parts.take(i + 1).joinToString("/", "/")}")
            return dir.copy(children = dir.children.toMutableList().also { it[idx] = rec(sub, i + 1) })
        }
        return rec(root, 0)
    }

    fun countFiles(dir: VDir): Int = dir.children.sumOf { if (it is VDir) countFiles(it) else 1 }
    fun countDirs(dir: VDir): Int = dir.children.sumOf { if (it is VDir) 1 + countDirs(it) else 0 }
    fun totalBytes(dir: VDir): Long = dir.children.sumOf { if (it is VDir) totalBytes(it) else (it as VFile).size }
}
