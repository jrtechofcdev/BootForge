package dev.bootforge.iso

import dev.bootforge.blockdev.RandomReader
import java.io.IOException
import java.io.InputStream

/** Trecho contínuo de dados dentro do arquivo .iso (posição em bytes + tamanho). */
data class Extent(val offset: Long, val length: Long)

/** Arquivo ou pasta dentro de uma imagem (ISO9660/Joliet/Rock Ridge ou UDF). */
data class TreeEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    /** Onde estão os dados (vários trechos em arquivos grandes). Vazio para pastas. */
    val extents: List<Extent>,
    internal val ref: Any? = null,
)

/**
 * Sistema de arquivos de uma imagem, somente leitura. Implementações: [IsoFileSystem]
 * (ISO9660 + Joliet + Rock Ridge) e [UdfFileSystem] (UDF — usado pelas ISOs do Windows).
 */
interface ImageTree {
    /** Nome do formato para mostrar ao usuário. */
    val formatName: String

    fun listDir(path: String = "/"): List<TreeEntry>

    fun lookup(path: String): TreeEntry? {
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        var dir = "/"
        var found: TreeEntry? = null
        for (p in parts) {
            found = listDir(dir).firstOrNull { it.name.equals(p, ignoreCase = true) } ?: return null
            dir = dir.trimEnd('/') + "/" + found.name
            if (!found.isDirectory && p != parts.last()) return null
        }
        return found
    }

    /** Percorre tudo (pastas antes do conteúdo). */
    fun walk(path: String = "/", maxDepth: Int = 64, visit: (String, TreeEntry) -> Unit) {
        if (maxDepth < 0) return
        for (e in listDir(path)) {
            val full = path.trimEnd('/') + "/" + e.name
            visit(full, e)
            if (e.isDirectory) walk(full, maxDepth - 1, visit)
        }
    }

    /** Abre os dados de um arquivo para leitura sequencial. */
    fun open(entry: TreeEntry): InputStream

    companion object {
        /**
         * Abre a árvore mais completa disponível: UDF (quando existe — ISOs do Windows têm os
         * arquivos reais só no UDF) ou ISO9660/Joliet/Rock Ridge.
         */
        fun open(reader: RandomReader): ImageTree? =
            runCatching { UdfFileSystem.open(reader) }.getOrNull() ?: IsoFileSystem.open(reader)
    }
}

/** Lê os trechos de um arquivo em sequência. */
internal class ExtentInputStream(private val reader: RandomReader, private val extents: List<Extent>, private val size: Long) : InputStream() {
    private var index = 0
    private var inExtent = 0L
    private var total = 0L

    override fun read(): Int {
        val b = ByteArray(1)
        return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (total >= size) return -1
        while (index < extents.size && inExtent >= extents[index].length) { index++; inExtent = 0 }
        if (index >= extents.size) throw IOException("Dados do arquivo terminaram antes do tamanho declarado")
        val e = extents[index]
        val n = minOf(len.toLong(), e.length - inExtent, size - total).toInt()
        // offset < 0: trecho reservado mas não gravado (UDF) → conteúdo é zero
        val r = if (e.offset < 0) { b.fill(0, off, off + n); n } else reader.read(e.offset + inExtent, b, off, n)
        if (r <= 0) throw IOException("Fim inesperado da imagem")
        inExtent += r
        total += r
        return r
    }
}
