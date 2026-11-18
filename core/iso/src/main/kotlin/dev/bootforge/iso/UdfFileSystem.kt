package dev.bootforge.iso

import dev.bootforge.blockdev.RandomReader
import java.io.IOException
import java.io.InputStream

/**
 * Leitor UDF (ECMA-167 / OSTA UDF 1.02–2.60), somente leitura.
 *
 * As ISOs oficiais do Windows são "ponte" ISO9660+UDF: a parte ISO9660 só tem um README;
 * os arquivos reais (sources/install.wim, efi/…) estão no UDF. Suporta partição física
 * (mapa tipo 1), File Entry e Extended File Entry, descritores curtos/longos e dados embutidos.
 * Não suporta partições virtuais/metadados (UDF 2.50+ em discos regraváveis) — não usadas em ISOs.
 */
class UdfFileSystem private constructor(
    private val reader: RandomReader,
    private val partitionStart: Long,
    private val blockSize: Int,
    private val root: TreeEntry,
) : ImageTree {

    override val formatName = "UDF"

    override fun listDir(path: String): List<TreeEntry> {
        val dir = if (path.trim('/').isEmpty()) root else (lookup(path) ?: throw IOException("Pasta não encontrada: $path"))
        if (!dir.isDirectory) throw IOException("Não é uma pasta: $path")
        return readDirectory(dir)
    }

    override fun open(entry: TreeEntry): InputStream {
        val inline = entry.ref as? ByteArray
        if (inline != null) return inline.copyOf(entry.size.toInt()).inputStream()
        return ExtentInputStream(reader, entry.extents, entry.size)
    }

    private fun readDirectory(dir: TreeEntry): List<TreeEntry> {
        if (dir.size > 64L shl 20) throw IOException("Diretório UDF grande demais (corrompido?)")
        val raw = open(dir).use { it.readBytes() }
        val out = mutableListOf<TreeEntry>()
        var p = 0
        while (p + 38 <= raw.size) {
            val tag = u16(raw, p)
            if (tag != 257) break // File Identifier Descriptor
            val chars = raw[p + 18].toInt() and 0xFF
            val lfi = raw[p + 19].toInt() and 0xFF
            val icbLbn = u32(raw, p + 20 + 4)
            val liu = u16(raw, p + 36)
            val nameOff = p + 38 + liu
            val deleted = chars and 0x04 != 0
            val parent = chars and 0x08 != 0
            if (!deleted && !parent && lfi > 0) {
                val name = dstring(raw, nameOff, lfi)
                runCatching { readFileEntry(icbLbn, name) }.getOrNull()?.let { out += it }
            }
            p += ((38 + liu + lfi + 3) / 4) * 4
        }
        return out
    }

    private fun blockOffset(lbn: Long) = (partitionStart + lbn) * blockSize

    /** Lê um File Entry (261) ou Extended File Entry (266). */
    private fun readFileEntry(lbn: Long, name: String): TreeEntry {
        val b = reader.readFully(blockOffset(lbn), blockSize)
        val tag = u16(b, 0)
        val (lEa, lAd, adStart) = when (tag) {
            261 -> Triple(u32(b, 168).toInt(), u32(b, 172).toInt(), 176)
            266 -> Triple(u32(b, 208).toInt(), u32(b, 212).toInt(), 216)
            else -> throw IOException("Entrada UDF inesperada (tag $tag)")
        }
        val fileType = b[16 + 11].toInt() and 0xFF
        val flags = u16(b, 16 + 18)
        val size = u64(b, 56)
        val adType = flags and 0x07
        val ads = adStart + lEa
        if (ads + lAd > b.size) throw IOException("Descritores de alocação fora do bloco")
        val isDir = fileType == 4
        return when (adType) {
            3 -> TreeEntry(name, isDir, size, emptyList(), b.copyOfRange(ads, ads + lAd)) // dados embutidos na entrada
            0, 1 -> TreeEntry(name, isDir, size, extents(b, ads, lAd, adType))
            else -> throw IOException("Tipo de descritor de alocação UDF não suportado: $adType")
        }
    }

    private fun extents(b: ByteArray, start: Int, len: Int, adType: Int): List<Extent> {
        val out = mutableListOf<Extent>()
        val adSize = if (adType == 0) 8 else 16
        var p = start
        var buf = b
        var end = start + len
        var guard = 0
        while (p + adSize <= end && guard++ < 100_000) {
            val raw = u32(buf, p)
            val length = raw and 0x3FFFFFFF
            val type = (raw ushr 30).toInt()
            val pos = u32(buf, p + 4)
            if (length == 0L) break
            when (type) {
                0 -> out += Extent(blockOffset(pos), length) // gravado
                1, 2 -> out += Extent(-1, length)          // alocado/não gravado: zeros
                3 -> { // continuação: os próximos descritores estão em outro bloco
                    buf = reader.readFully(blockOffset(pos), blockSize)
                    p = 24 // Allocation Extent Descriptor: tag(16) + prev(4) + length(4)
                    end = 24 + u32(buf, 20).toInt()
                    continue
                }
            }
            p += adSize
        }
        return out
    }

    companion object {
        /** Abre o UDF da imagem; lança exceção se não houver UDF. */
        fun open(reader: RandomReader): UdfFileSystem {
            val sector = 2048
            if (reader.size < 257L * sector) throw IOException("Pequeno demais para UDF")
            val avdp = reader.readFully(256L * sector, sector)
            if (u16(avdp, 0) != 2) throw IOException("Âncora UDF (AVDP) não encontrada")
            val vdsLen = u32(avdp, 16)
            val vdsLoc = u32(avdp, 20)
            var partStart = -1L
            var partNumber = -1
            var blockSize = sector
            var fsdLbn = -1L
            var i = 0L
            while (i * sector < vdsLen && i < 64) {
                val d = reader.readFully((vdsLoc + i) * sector, sector)
                when (u16(d, 0)) {
                    5 -> { partNumber = u16(d, 22); partStart = u32(d, 188) } // Partition Descriptor
                    6 -> { blockSize = u32(d, 212).toInt(); fsdLbn = u32(d, 248 + 4) } // Logical Volume Descriptor
                    8 -> break // Terminating Descriptor
                }
                i++
            }
            if (partStart < 0 || fsdLbn < 0) throw IOException("Descritores de volume UDF incompletos")
            if (blockSize != sector) throw IOException("Bloco UDF de $blockSize bytes não suportado")
            val tmp = UdfFileSystem(reader, partStart, blockSize, TreeEntry("/", true, 0, emptyList()))
            val fsd = reader.readFully(tmp.blockOffset(fsdLbn), sector)
            if (u16(fsd, 0) != 256) throw IOException("File Set Descriptor UDF não encontrado")
            val rootLbn = u32(fsd, 400 + 4)
            val root = tmp.readFileEntry(rootLbn, "/")
            if (!root.isDirectory) throw IOException("Raiz UDF inválida")
            if (partNumber < 0) throw IOException("Partição UDF não encontrada")
            return UdfFileSystem(reader, partStart, blockSize, root)
        }

        /** "dstring" OSTA: 1º byte = compressão (8 = 8 bits, 16 = UTF-16BE). */
        internal fun dstring(b: ByteArray, off: Int, len: Int): String {
            val comp = b[off].toInt() and 0xFF
            return when (comp) {
                8 -> String(b, off + 1, len - 1, Charsets.ISO_8859_1)
                16 -> String(b, off + 1, len - 1, Charsets.UTF_16BE)
                else -> String(b, off + 1, len - 1, Charsets.ISO_8859_1)
            }.trimEnd('\u0000')
        }

        internal fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        internal fun u32(b: ByteArray, o: Int) = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)
        internal fun u64(b: ByteArray, o: Int) = u32(b, o) or (u32(b, o + 4) shl 32)
    }
}
