package dev.bootforge.fs.exfat

import dev.bootforge.blockdev.BlockDevice
import dev.bootforge.blockdev.readBlocks
import java.io.IOException
import java.io.InputStream

/** Arquivo ou pasta dentro de um volume exFAT. */
data class ExFatEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val firstCluster: Long,
    /** true quando os clusters são contíguos e a FAT não é usada (flag NoFatChain). */
    val contiguous: Boolean,
    val attributes: Int,
    /** Data de modificação em milissegundos desde 1970 (fuso local do arquivo), ou null. */
    val modifiedMillis: Long?,
)

/**
 * Leitor exFAT — SOMENTE LEITURA nesta versão.
 *
 * Usado para mostrar espaço usado/livre e contar as ISOs na partição de dados do Ventoy.
 * A escrita (copiar/renomear/excluir arquivos) faz parte da próxima etapa (gerenciador de arquivos).
 */
class ExFatVolume private constructor(private val dev: BlockDevice) {

    val bytesPerSector: Int
    val sectorsPerCluster: Int
    val clusterCount: Long
    val volumeSerial: Long
    val bootChecksumValid: Boolean
    private val fatOffset: Long
    private val heapOffset: Long
    private val rootCluster: Long

    val clusterSize: Int get() = bytesPerSector * sectorsPerCluster
    val totalBytes: Long get() = clusterCount * clusterSize

    var label: String? = null
        private set
    private var bitmapCluster = 0L
    private var bitmapLength = 0L

    // Cache simples de setores da FAT (a FAT de discos grandes pode ter dezenas de MB).
    private val fatCache = object : LinkedHashMap<Long, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > 256
    }

    init {
        val region = dev.readBlocks(0, 12)
        if (String(region, 3, 8, Charsets.US_ASCII) != "EXFAT   ") throw IOException("Não é um volume exFAT")
        bytesPerSector = 1 shl region[108].toInt()
        sectorsPerCluster = 1 shl region[109].toInt()
        if (bytesPerSector != dev.blockSize) throw IOException("Setor exFAT ($bytesPerSector) difere do dispositivo (${dev.blockSize})")
        fatOffset = u32(region, 80)
        heapOffset = u32(region, 88)
        clusterCount = u32(region, 92)
        rootCluster = u32(region, 96)
        volumeSerial = u32(region, 100)
        val expected = ExFatFormatter.bootChecksum(region, bytesPerSector)
        bootChecksumValid = u32(region, 11 * bytesPerSector) == expected
        readRootMetadata()
    }

    private fun readRootMetadata() {
        val raw = readChain(rootCluster, contiguous = false, length = -1)
        var i = 0
        while (i + 32 <= raw.size) {
            when (raw[i].toInt() and 0xFF) {
                0x00 -> break
                0x83 -> {
                    val n = raw[i + 1].toInt() and 0xFF
                    label = String(raw, i + 2, n.coerceAtMost(11) * 2, Charsets.UTF_16LE)
                }
                0x81 -> if (raw[i + 1].toInt() and 1 == 0) { // primeiro bitmap
                    bitmapCluster = u32(raw, i + 20); bitmapLength = u64(raw, i + 24)
                }
            }
            i += 32
        }
        if (bitmapCluster == 0L) throw IOException("Bitmap de alocação exFAT não encontrado")
    }

    private fun clusterLba(c: Long) = heapOffset + (c - 2) * sectorsPerCluster

    private fun fatNext(cluster: Long): Long {
        val byteOff = cluster * 4
        val sector = fatOffset + byteOff / bytesPerSector
        val data = fatCache.getOrPut(sector) { dev.readBlocks(sector, 1) }
        val v = u32(data, (byteOff % bytesPerSector).toInt())
        return if (v in 2..(clusterCount + 1)) v else -1
    }

    /** Lista de clusters de um arquivo/pasta. */
    private fun clusters(first: Long, contiguous: Boolean, length: Long): List<Long> {
        if (first < 2) return emptyList()
        if (contiguous) {
            val n = (length + clusterSize - 1) / clusterSize
            return (0 until n).map { first + it }
        }
        val out = mutableListOf<Long>()
        var c = first
        while (c >= 2 && out.size <= clusterCount) {
            out += c
            c = fatNext(c)
        }
        return out
    }

    private fun readChain(first: Long, contiguous: Boolean, length: Long): ByteArray {
        val list = clusters(first, contiguous, length)
        val out = ByteArray(list.size * clusterSize)
        list.forEachIndexed { i, c -> dev.read(clusterLba(c), out, i * clusterSize, clusterSize) }
        return out
    }

    /** Conta os clusters livres percorrendo o bitmap de alocação. */
    fun freeClusters(): Long {
        val raw = readChain(bitmapCluster, contiguous = false, length = bitmapLength)
        var used = 0L
        val fullBytes = (clusterCount / 8).toInt()
        for (i in 0 until fullBytes) used += Integer.bitCount(raw[i].toInt() and 0xFF)
        val rest = (clusterCount % 8).toInt()
        if (rest > 0) used += Integer.bitCount((raw[fullBytes].toInt() and 0xFF) and ((1 shl rest) - 1))
        return clusterCount - used
    }

    fun freeBytes(): Long = freeClusters() * clusterSize

    private fun parseDirectory(raw: ByteArray): List<ExFatEntry> {
        val out = mutableListOf<ExFatEntry>()
        var i = 0
        while (i + 32 <= raw.size) {
            val type = raw[i].toInt() and 0xFF
            if (type == 0x00) break
            if (type != 0x85) { i += 32; continue }
            val secondary = raw[i + 1].toInt() and 0xFF
            val attrs = u16(raw, i + 4)
            val modified = dosTimestamp(u32(raw, i + 12))
            val s = i + 32
            if (s + 32 > raw.size || (raw[s].toInt() and 0xFF) != 0xC0) { i += 32; continue }
            val flags = raw[s + 1].toInt() and 0xFF
            val nameLen = raw[s + 3].toInt() and 0xFF
            val first = u32(raw, s + 20)
            val dataLen = u64(raw, s + 24)
            val sb = StringBuilder()
            var n = 2
            while (n <= secondary && sb.length < nameLen) {
                val e = i + n * 32
                if (e + 32 > raw.size || (raw[e].toInt() and 0xFF) != 0xC1) break
                sb.append(String(raw, e + 2, 30, Charsets.UTF_16LE))
                n++
            }
            out += ExFatEntry(
                name = sb.toString().take(nameLen),
                isDirectory = attrs and 0x10 != 0,
                size = dataLen,
                firstCluster = first,
                contiguous = flags and 0x02 != 0,
                attributes = attrs,
                modifiedMillis = modified,
            )
            i += (secondary + 1) * 32
        }
        return out
    }

    private fun readDirectory(entry: ExFatEntry?): List<ExFatEntry> =
        if (entry == null) parseDirectory(readChain(rootCluster, false, -1))
        else parseDirectory(readChain(entry.firstCluster, entry.contiguous, entry.size))

    fun list(path: String = "/"): List<ExFatEntry> {
        if (path.trim('/').isEmpty()) return readDirectory(null)
        val e = resolve(path) ?: throw IOException("Pasta não encontrada: $path")
        if (!e.isDirectory) throw IOException("Não é uma pasta: $path")
        return readDirectory(e)
    }

    fun resolve(path: String): ExFatEntry? {
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        var current: ExFatEntry? = null
        for (p in parts) {
            if (current != null && !current.isDirectory) return null
            current = readDirectory(current).firstOrNull { it.name.equals(p, ignoreCase = true) } ?: return null
        }
        return current
    }

    /** Percorre todos os arquivos recursivamente (limite de profundidade evita laços em volumes corrompidos). */
    fun walk(path: String = "/", maxDepth: Int = 32, visit: (String, ExFatEntry) -> Unit) {
        if (maxDepth < 0) return
        for (e in list(path)) {
            val full = path.trimEnd('/') + "/" + e.name
            visit(full, e)
            if (e.isDirectory) walk(full, maxDepth - 1, visit)
        }
    }

    /** Abre um arquivo para leitura sequencial. */
    fun openFile(path: String): InputStream {
        val e = resolve(path) ?: throw IOException("Arquivo não encontrado: $path")
        if (e.isDirectory) throw IOException("É uma pasta: $path")
        val list = clusters(e.firstCluster, e.contiguous, e.size)
        return object : InputStream() {
            private var pos = 0L
            private val buf = ByteArray(clusterSize)
            private var bufIndex = -1
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= e.size) return -1
                val idx = (pos / clusterSize).toInt()
                if (idx >= list.size) throw IOException("Cadeia de clusters menor que o arquivo")
                if (idx != bufIndex) { dev.read(clusterLba(list[idx]), buf); bufIndex = idx }
                val inCluster = (pos % clusterSize).toInt()
                val n = minOf(len.toLong(), (clusterSize - inCluster).toLong(), e.size - pos).toInt()
                buf.copyInto(b, off, inCluster, inCluster + n)
                pos += n
                return n
            }
        }
    }

    companion object {
        fun open(dev: BlockDevice): ExFatVolume = ExFatVolume(dev)

        private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        private fun u32(b: ByteArray, o: Int) = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)
        private fun u64(b: ByteArray, o: Int) = u32(b, o) or (u32(b, o + 4) shl 32)

        /** Converte o timestamp DOS (data+hora de 32 bits) para milissegundos (UTC aproximado). */
        private fun dosTimestamp(v: Long): Long? {
            if (v == 0L) return null
            val sec = ((v and 0x1F) * 2).toInt()
            val min = ((v ushr 5) and 0x3F).toInt()
            val hour = ((v ushr 11) and 0x1F).toInt()
            val day = ((v ushr 16) and 0x1F).toInt()
            val month = ((v ushr 21) and 0x0F).toInt()
            val year = 1980 + ((v ushr 25) and 0x7F).toInt()
            return runCatching {
                java.time.LocalDateTime.of(year, month, day, hour, min, sec)
                    .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
            }.getOrNull()
        }
    }
}
