package dev.bootforge.fs.fat

import dev.bootforge.blockdev.BlockDevice
import dev.bootforge.blockdev.readBlocks
import java.io.IOException

/** Entrada de diretório FAT (arquivo ou pasta). */
data class FatEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val firstCluster: Long,
    val attributes: Int,
)

/**
 * Leitor FAT12/FAT16/FAT32 — SOMENTE LEITURA.
 *
 * Usado para inspecionar a partição VTOYEFI do Ventoy (FAT16, 32 MiB) sem montar nada:
 * ler `/grub/grub.cfg` (versão), verificar arquivos de Secure Boot, listar temas etc.
 * Suporta nomes longos (LFN/VFAT).
 *
 * Toda a tabela FAT é carregada na memória: adequado para volumes pequenos como o VTOYEFI.
 */
class FatVolume private constructor(private val dev: BlockDevice) {

    val bytesPerSector: Int
    val sectorsPerCluster: Int
    val type: Type
    val label: String?
    val totalClusters: Long

    private val fatStart: Long
    private val rootDirStart: Long
    private val rootDirSectors: Int
    private val dataStart: Long
    private val rootCluster: Long
    private val fat: ByteArray

    enum class Type { FAT12, FAT16, FAT32 }

    init {
        val bs = dev.readBlocks(0, 1)
        if ((bs[510].toInt() and 0xFF) != 0x55 || (bs[511].toInt() and 0xFF) != 0xAA) throw IOException("Setor de boot FAT inválido")
        bytesPerSector = u16(bs, 11)
        if (bytesPerSector != dev.blockSize) throw IOException("Setor FAT ($bytesPerSector) difere do dispositivo (${dev.blockSize})")
        sectorsPerCluster = bs[13].toInt() and 0xFF
        val reserved = u16(bs, 14)
        val nFats = bs[16].toInt() and 0xFF
        val rootEntries = u16(bs, 17)
        val total = if (u16(bs, 19) != 0) u16(bs, 19).toLong() else u32(bs, 32)
        val fatSize = if (u16(bs, 22) != 0) u16(bs, 22).toLong() else u32(bs, 36)
        if (sectorsPerCluster == 0 || nFats == 0 || fatSize == 0L) throw IOException("BPB FAT inválido")
        rootDirSectors = (rootEntries * 32 + bytesPerSector - 1) / bytesPerSector
        fatStart = reserved.toLong()
        rootDirStart = fatStart + nFats * fatSize
        dataStart = rootDirStart + rootDirSectors
        totalClusters = (total - dataStart) / sectorsPerCluster
        type = when {
            totalClusters < 4085 -> Type.FAT12
            totalClusters < 65525 -> Type.FAT16
            else -> Type.FAT32
        }
        rootCluster = if (type == Type.FAT32) u32(bs, 44) else 0
        val labelOff = if (type == Type.FAT32) 71 else 43
        label = String(bs, labelOff, 11, Charsets.US_ASCII).trim().ifEmpty { null }
        fat = dev.readBlocks(fatStart, fatSize.toInt())
    }

    /** Próximo cluster da cadeia (ou -1 no fim da cadeia). */
    private fun next(cluster: Long): Long {
        val v = when (type) {
            Type.FAT12 -> {
                val off = (cluster * 3 / 2).toInt()
                val raw = u16(fat, off)
                (if (cluster and 1L == 0L) raw and 0xFFF else raw ushr 4).toLong().let { if (it >= 0xFF8) -1 else it }
            }
            Type.FAT16 -> u16(fat, (cluster * 2).toInt()).toLong().let { if (it >= 0xFFF8) -1 else it }
            Type.FAT32 -> (u32(fat, (cluster * 4).toInt()) and 0x0FFFFFFF).let { if (it >= 0x0FFFFFF8) -1 else it }
        }
        return if (v in 2..(totalClusters + 1)) v else -1
    }

    private fun chain(first: Long): List<Long> {
        val out = mutableListOf<Long>()
        var c = first
        while (c >= 2 && out.size <= totalClusters) {
            out += c
            c = next(c)
        }
        return out
    }

    private fun readClusters(first: Long): ByteArray {
        val clusters = chain(first)
        val clusterBytes = sectorsPerCluster * bytesPerSector
        val out = ByteArray(clusters.size * clusterBytes)
        clusters.forEachIndexed { i, c ->
            dev.read(dataStart + (c - 2) * sectorsPerCluster, out, i * clusterBytes, clusterBytes)
        }
        return out
    }

    private fun readDirectoryRaw(cluster: Long): ByteArray =
        if (cluster == 0L && type != Type.FAT32) dev.readBlocks(rootDirStart, rootDirSectors)
        else readClusters(if (cluster == 0L) rootCluster else cluster)

    /** Lista o conteúdo de uma pasta (caminho com "/" — ex.: "/EFI/BOOT"). */
    fun list(path: String = "/"): List<FatEntry> {
        val dir = if (path.trim('/').isEmpty()) 0L else (resolve(path) ?: throw IOException("Pasta não encontrada: $path")).let {
            if (!it.isDirectory) throw IOException("Não é uma pasta: $path")
            it.firstCluster
        }
        return parseDirectory(readDirectoryRaw(dir))
    }

    private fun parseDirectory(raw: ByteArray): List<FatEntry> {
        val out = mutableListOf<FatEntry>()
        val lfnParts = sortedMapOf<Int, String>()
        var i = 0
        while (i + 32 <= raw.size) {
            val first = raw[i].toInt() and 0xFF
            if (first == 0) break
            val attr = raw[i + 11].toInt() and 0xFF
            if (first == 0xE5) { lfnParts.clear(); i += 32; continue }
            if (attr == 0x0F) {
                val seq = first and 0x1F
                val chars = ByteArray(26)
                raw.copyInto(chars, 0, i + 1, i + 11)
                raw.copyInto(chars, 10, i + 14, i + 26)
                raw.copyInto(chars, 22, i + 28, i + 32)
                lfnParts[seq] = String(chars, Charsets.UTF_16LE).substringBefore('\u0000').trimEnd('￿')
                i += 32; continue
            }
            if (attr and 0x08 != 0) { lfnParts.clear(); i += 32; continue } // rótulo de volume
            val shortName = run {
                val base = String(raw, i, 8, Charsets.ISO_8859_1).trimEnd()
                val ext = String(raw, i + 8, 3, Charsets.ISO_8859_1).trimEnd()
                if (ext.isEmpty()) base else "$base.$ext"
            }
            val name = if (lfnParts.isNotEmpty()) lfnParts.values.joinToString("") else shortName
            lfnParts.clear()
            if (name != "." && name != "..") {
                val hi = if (type == Type.FAT32) u16(raw, i + 20).toLong() shl 16 else 0L
                out += FatEntry(name, attr and 0x10 != 0, u32(raw, i + 28), hi or u16(raw, i + 26).toLong(), attr)
            }
            i += 32
        }
        return out
    }

    /** Localiza um arquivo/pasta (comparação sem diferenciar maiúsculas, como no FAT). */
    fun resolve(path: String): FatEntry? {
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        var entries = parseDirectory(readDirectoryRaw(0))
        var found: FatEntry? = null
        for ((idx, part) in parts.withIndex()) {
            found = entries.firstOrNull { it.name.equals(part, ignoreCase = true) } ?: return null
            if (idx < parts.lastIndex) {
                if (!found.isDirectory) return null
                entries = parseDirectory(readDirectoryRaw(found.firstCluster))
            }
        }
        return found
    }

    fun exists(path: String): Boolean = resolve(path) != null

    /** Lê o arquivo inteiro na memória (adequado para arquivos de configuração e imagens pequenas). */
    fun readFile(path: String): ByteArray {
        val e = resolve(path) ?: throw IOException("Arquivo não encontrado: $path")
        if (e.isDirectory) throw IOException("É uma pasta: $path")
        if (e.size == 0L) return ByteArray(0)
        val data = readClusters(e.firstCluster)
        if (data.size < e.size) throw IOException("Cadeia de clusters menor que o tamanho do arquivo: $path")
        return data.copyOf(e.size.toInt())
    }

    /** Percorre todos os arquivos recursivamente. */
    fun walk(path: String = "/", visit: (path: String, entry: FatEntry) -> Unit) {
        for (e in list(path)) {
            val full = (path.trimEnd('/') + "/" + e.name)
            visit(full, e)
            if (e.isDirectory) walk(full, visit)
        }
    }

    companion object {
        fun open(dev: BlockDevice): FatVolume = FatVolume(dev)

        private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        private fun u32(b: ByteArray, o: Int) = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)
    }
}
