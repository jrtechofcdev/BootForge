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
    internal val location: FatLocation? = null,
)

/** Posição da entrada curta (8.3) e das entradas de nome longo que a precedem. */
data class FatLocation internal constructor(internal val dirCluster: Long, internal val offset: Int, internal val lfnCount: Int)

/**
 * Leitor FAT12/FAT16/FAT32 com escrita CONTROLADA.
 *
 * Usado na partição VTOYEFI do Ventoy (FAT16, 32 MiB):
 *  - leitura: versão (`/grub/grub.cfg`), arquivos de Secure Boot, temas…
 *  - escrita (somente o necessário para a ferramenta oficial de Secure Boot): substituir o
 *    conteúdo de um arquivo existente e excluir arquivos. Nunca cria pastas nem renomeia.
 *
 * A FAT inteira fica na memória (adequado para volumes pequenos como a VTOYEFI); [flush]
 * grava a FAT em TODAS as cópias.
 */
class FatVolume private constructor(private val dev: BlockDevice, val isWritable: Boolean) {

    val bytesPerSector: Int
    val sectorsPerCluster: Int
    val type: Type
    val label: String?
    val totalClusters: Long

    private val fatStart: Long
    private val fatSizeSectors: Long
    private val numberOfFats: Int
    private val rootDirStart: Long
    private val rootDirSectors: Int
    private val dataStart: Long
    private val rootCluster: Long
    private val fat: ByteArray
    private var fatDirty = false

    enum class Type { FAT12, FAT16, FAT32 }

    init {
        val bs = dev.readBlocks(0, 1)
        if ((bs[510].toInt() and 0xFF) != 0x55 || (bs[511].toInt() and 0xFF) != 0xAA) throw IOException("Setor de boot FAT inválido")
        bytesPerSector = u16(bs, 11)
        if (bytesPerSector != dev.blockSize) throw IOException("Setor FAT ($bytesPerSector) difere do dispositivo (${dev.blockSize})")
        sectorsPerCluster = bs[13].toInt() and 0xFF
        val reserved = u16(bs, 14)
        numberOfFats = bs[16].toInt() and 0xFF
        val rootEntries = u16(bs, 17)
        val total = if (u16(bs, 19) != 0) u16(bs, 19).toLong() else u32(bs, 32)
        fatSizeSectors = if (u16(bs, 22) != 0) u16(bs, 22).toLong() else u32(bs, 36)
        if (sectorsPerCluster == 0 || numberOfFats == 0 || fatSizeSectors == 0L) throw IOException("BPB FAT inválido")
        rootDirSectors = (rootEntries * 32 + bytesPerSector - 1) / bytesPerSector
        fatStart = reserved.toLong()
        rootDirStart = fatStart + numberOfFats * fatSizeSectors
        dataStart = rootDirStart + rootDirSectors
        totalClusters = (total - dataStart) / sectorsPerCluster
        type = when {
            totalClusters < 4085 -> Type.FAT12
            totalClusters < 65525 -> Type.FAT16
            else -> Type.FAT32
        }
        if (isWritable && type == Type.FAT12) throw IOException("Gravação em FAT12 não suportada")
        if (isWritable && dev.isReadOnly) throw IOException("Dispositivo aberto somente para leitura")
        rootCluster = if (type == Type.FAT32) u32(bs, 44) else 0
        val labelOff = if (type == Type.FAT32) 71 else 43
        label = String(bs, labelOff, 11, Charsets.US_ASCII).trim().ifEmpty { null }
        fat = dev.readBlocks(fatStart, fatSizeSectors.toInt())
    }

    private val clusterBytes get() = sectorsPerCluster * bytesPerSector
    private val eocMin get() = if (type == Type.FAT32) 0x0FFFFFF8L else if (type == Type.FAT16) 0xFFF8L else 0xFF8L

    private fun fatGet(cluster: Long): Long = when (type) {
        Type.FAT12 -> {
            val raw = u16(fat, (cluster * 3 / 2).toInt())
            (if (cluster and 1L == 0L) raw and 0xFFF else raw ushr 4).toLong()
        }
        Type.FAT16 -> u16(fat, (cluster * 2).toInt()).toLong()
        Type.FAT32 -> u32(fat, (cluster * 4).toInt()) and 0x0FFFFFFF
    }

    private fun fatSet(cluster: Long, value: Long) {
        when (type) {
            Type.FAT16 -> put16(fat, (cluster * 2).toInt(), value.toInt())
            Type.FAT32 -> {
                val o = (cluster * 4).toInt()
                val keep = u32(fat, o) and 0xF0000000L
                put32(fat, o, keep or (value and 0x0FFFFFFF))
            }
            Type.FAT12 -> throw IOException("FAT12 somente leitura")
        }
        fatDirty = true
    }

    /** Próximo cluster da cadeia (ou -1 no fim da cadeia). */
    private fun next(cluster: Long): Long {
        val v = fatGet(cluster)
        if (v >= eocMin) return -1
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

    private fun clusterLba(c: Long) = dataStart + (c - 2) * sectorsPerCluster

    private fun readClusters(first: Long): ByteArray {
        val clusters = chain(first)
        val out = ByteArray(clusters.size * clusterBytes)
        clusters.forEachIndexed { i, c -> dev.read(clusterLba(c), out, i * clusterBytes, clusterBytes) }
        return out
    }

    private fun readDirectoryRaw(cluster: Long): ByteArray =
        if (cluster == 0L && type != Type.FAT32) dev.readBlocks(rootDirStart, rootDirSectors)
        else readClusters(if (cluster == 0L) rootCluster else cluster)

    /** Lista o conteúdo de uma pasta (caminho com "/" — ex.: "/EFI/BOOT"). */
    fun list(path: String = "/"): List<FatEntry> {
        val dir = dirCluster(path)
        return parseDirectory(readDirectoryRaw(dir), dir)
    }

    private fun dirCluster(path: String): Long =
        if (path.trim('/').isEmpty()) 0L else (resolve(path) ?: throw IOException("Pasta não encontrada: $path")).let {
            if (!it.isDirectory) throw IOException("Não é uma pasta: $path")
            it.firstCluster
        }

    private fun parseDirectory(raw: ByteArray, dirCluster: Long): List<FatEntry> {
        val out = mutableListOf<FatEntry>()
        val lfnParts = sortedMapOf<Int, String>()
        var lfnCount = 0
        var i = 0
        while (i + 32 <= raw.size) {
            val first = raw[i].toInt() and 0xFF
            if (first == 0) break
            val attr = raw[i + 11].toInt() and 0xFF
            if (first == 0xE5) { lfnParts.clear(); lfnCount = 0; i += 32; continue }
            if (attr == 0x0F) {
                val seq = first and 0x1F
                val chars = ByteArray(26)
                raw.copyInto(chars, 0, i + 1, i + 11)
                raw.copyInto(chars, 10, i + 14, i + 26)
                raw.copyInto(chars, 22, i + 28, i + 32)
                lfnParts[seq] = String(chars, Charsets.UTF_16LE).substringBefore('\u0000').trimEnd('￿')
                lfnCount++
                i += 32; continue
            }
            if (attr and 0x08 != 0) { lfnParts.clear(); lfnCount = 0; i += 32; continue } // rótulo de volume
            val shortName = run {
                val base = String(raw, i, 8, Charsets.ISO_8859_1).trimEnd()
                val ext = String(raw, i + 8, 3, Charsets.ISO_8859_1).trimEnd()
                if (ext.isEmpty()) base else "$base.$ext"
            }
            val name = if (lfnParts.isNotEmpty()) lfnParts.values.joinToString("") else shortName
            val lfns = if (lfnParts.isNotEmpty()) lfnCount else 0
            lfnParts.clear(); lfnCount = 0
            if (name != "." && name != "..") {
                val hi = if (type == Type.FAT32) u16(raw, i + 20).toLong() shl 16 else 0L
                out += FatEntry(name, attr and 0x10 != 0, u32(raw, i + 28), hi or u16(raw, i + 26).toLong(), attr, FatLocation(dirCluster, i, lfns))
            }
            i += 32
        }
        return out
    }

    /** Localiza um arquivo/pasta (comparação sem diferenciar maiúsculas, como no FAT). */
    fun resolve(path: String): FatEntry? {
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        var entries = parseDirectory(readDirectoryRaw(0), 0)
        var found: FatEntry? = null
        for ((idx, part) in parts.withIndex()) {
            found = entries.firstOrNull { it.name.equals(part, ignoreCase = true) } ?: return null
            if (idx < parts.lastIndex) {
                if (!found.isDirectory) return null
                entries = parseDirectory(readDirectoryRaw(found.firstCluster), found.firstCluster)
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

    /** Abre um arquivo para leitura sequencial (adequado para arquivos grandes). */
    fun openFile(path: String): java.io.InputStream {
        val e = resolve(path) ?: throw IOException("Arquivo não encontrado: $path")
        if (e.isDirectory) throw IOException("É uma pasta: $path")
        val clusters = if (e.size == 0L) emptyList() else chain(e.firstCluster)
        if (clusters.size.toLong() * clusterBytes < e.size) throw IOException("Cadeia de clusters menor que o arquivo: $path")
        return object : java.io.InputStream() {
            private var pos = 0L
            private val buf = ByteArray(clusterBytes)
            private var bufIdx = -1
            override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= e.size) return -1
                val idx = (pos / clusterBytes).toInt()
                if (idx != bufIdx) { dev.read(clusterLba(clusters[idx]), buf); bufIdx = idx }
                val inC = (pos % clusterBytes).toInt()
                val n = minOf(len.toLong(), (clusterBytes - inC).toLong(), e.size - pos).toInt()
                buf.copyInto(b, off, inC, inC + n)
                pos += n
                return n
            }
        }
    }

    /** Percorre todos os arquivos recursivamente. */
    fun walk(path: String = "/", visit: (path: String, entry: FatEntry) -> Unit) {
        for (e in list(path)) {
            val full = (path.trimEnd('/') + "/" + e.name)
            visit(full, e)
            if (e.isDirectory) walk(full, visit)
        }
    }

    // ================================================================= escrita

    private fun requireWritable() { if (!isWritable) throw IOException("Volume FAT aberto somente para leitura") }

    private fun freeChain(first: Long) {
        for (c in chain(first)) fatSet(c, 0)
    }

    private fun allocate(n: Int): List<Long> {
        val out = mutableListOf<Long>()
        var c = 2L
        while (out.size < n && c <= totalClusters + 1) {
            if (fatGet(c) == 0L) out += c
            c++
        }
        if (out.size < n) throw IOException("Espaço insuficiente na partição FAT")
        val eoc = if (type == Type.FAT16) 0xFFFFL else 0x0FFFFFFFL // marca de fim de cadeia
        out.forEachIndexed { i, cl -> fatSet(cl, if (i == out.lastIndex) eoc else out[i + 1]) }
        return out
    }

    /** Grava bytes da entrada de diretório na posição dela (leitura-modificação-escrita do setor). */
    private fun writeDirBytes(dirCluster: Long, offset: Int, data: ByteArray) {
        val sectorInDir = offset / bytesPerSector
        val lba = if (dirCluster == 0L && type != Type.FAT32) {
            rootDirStart + sectorInDir
        } else {
            val clusters = chain(if (dirCluster == 0L) rootCluster else dirCluster)
            val idx = sectorInDir / sectorsPerCluster
            clusterLba(clusters[idx]) + sectorInDir % sectorsPerCluster
        }
        val sector = dev.readBlocks(lba, 1)
        data.copyInto(sector, offset % bytesPerSector)
        dev.write(lba, sector)
    }

    /**
     * Substitui o conteúdo de um arquivo EXISTENTE (mantém nome e entradas de nome longo).
     * Os clusters novos são gravados antes de a entrada passar a apontar para eles.
     */
    fun replaceFile(path: String, data: ByteArray) {
        requireWritable()
        val e = resolve(path) ?: throw IOException("Arquivo não encontrado: $path")
        if (e.isDirectory) throw IOException("É uma pasta: $path")
        val loc = e.location!!
        val n = ((data.size + clusterBytes - 1) / clusterBytes)
        val oldFirst = e.firstCluster
        // libera a cadeia antiga primeiro na memória para poder reaproveitar o espaço
        if (oldFirst >= 2) freeChain(oldFirst)
        val clusters = if (n == 0) emptyList() else allocate(n)
        val buf = ByteArray(clusterBytes)
        clusters.forEachIndexed { i, c ->
            buf.fill(0)
            val start = i * clusterBytes
            data.copyInto(buf, 0, start, minOf(data.size, start + clusterBytes))
            dev.write(clusterLba(c), buf)
        }
        flush() // FAT nova gravada antes da entrada
        val first = clusters.firstOrNull() ?: 0L
        val entry = ByteArray(32)
        val raw = readDirectoryRaw(loc.dirCluster)
        raw.copyInto(entry, 0, loc.offset, loc.offset + 32)
        put16(entry, 26, (first and 0xFFFF).toInt())
        if (type == Type.FAT32) put16(entry, 20, ((first ushr 16) and 0xFFFF).toInt())
        put32(entry, 28, data.size.toLong())
        writeDirBytes(loc.dirCluster, loc.offset, entry)
        dev.flush()
    }

    /** Exclui um arquivo (entrada curta + entradas de nome longo marcadas como apagadas). */
    fun deleteFile(path: String) {
        requireWritable()
        val e = resolve(path) ?: throw IOException("Arquivo não encontrado: $path")
        if (e.isDirectory) throw IOException("Exclusão de pastas não suportada: $path")
        val loc = e.location!!
        for (k in 0..loc.lfnCount) writeDirBytes(loc.dirCluster, loc.offset - k * 32, byteArrayOf(0xE5.toByte()))
        if (e.firstCluster >= 2) freeChain(e.firstCluster)
        flush()
    }

    /** Grava a FAT em todas as cópias. */
    fun flush() {
        if (!fatDirty) return
        for (k in 0 until numberOfFats) dev.write(fatStart + k * fatSizeSectors, fat)
        fatDirty = false
        dev.flush()
    }

    companion object {
        fun open(dev: BlockDevice, writable: Boolean = false): FatVolume = FatVolume(dev, writable)

        private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        private fun u32(b: ByteArray, o: Int) = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)
        private fun put16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte() }
        private fun put32(b: ByteArray, o: Int, v: Long) { for (i in 0 until 4) b[o + i] = (v ushr (8 * i)).toByte() }
    }
}
