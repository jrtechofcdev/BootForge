package dev.bootforge.iso

import dev.bootforge.blockdev.RandomReader
import java.io.IOException

/** Entrada de diretório ISO9660 (ou Joliet). */
data class IsoEntry(
    val name: String,
    val isDirectory: Boolean,
    val extentLba: Long,
    val size: Long,
    /** Arquivo dividido em várias partes (ISO9660 nível 3, arquivos > 4 GiB). */
    val multiExtent: Boolean,
)

/**
 * Leitor do sistema de arquivos ISO9660 (ECMA-119) com extensão Joliet (nomes longos/Unicode).
 * Somente leitura. Base da identificação de imagens e, na Etapa 5, do editor de ISO.
 */
class IsoFileSystem private constructor(
    private val reader: RandomReader,
    val joliet: Boolean,
    private val root: IsoEntry,
) {

    fun list(path: String = "/"): List<IsoEntry> {
        val dir = if (path.trim('/').isEmpty()) root else (find(path) ?: throw IOException("Pasta não encontrada: $path"))
        if (!dir.isDirectory) throw IOException("Não é uma pasta: $path")
        return readDirectory(dir)
    }

    fun find(path: String): IsoEntry? {
        var cur = root
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            if (!cur.isDirectory) return null
            cur = readDirectory(cur).firstOrNull { it.name.equals(part, ignoreCase = true) } ?: return null
        }
        return cur
    }

    /** Lê um arquivo pequeno inteiro (até [max] bytes). */
    fun readSmallFile(entry: IsoEntry, max: Int = 1 shl 20): ByteArray {
        if (entry.isDirectory) throw IOException("É uma pasta")
        val n = minOf(entry.size, max.toLong()).toInt()
        return reader.readFully(entry.extentLba * SECTOR, n)
    }

    private fun readDirectory(dir: IsoEntry): List<IsoEntry> {
        if (dir.size > 64L shl 20) throw IOException("Diretório ISO grande demais (corrompido?)")
        val raw = reader.readFully(dir.extentLba * SECTOR, dir.size.toInt())
        val out = mutableListOf<IsoEntry>()
        var pos = 0
        while (pos < raw.size) {
            val len = raw[pos].toInt() and 0xFF
            if (len == 0) { // registros não cruzam setores: pula para o próximo setor
                pos = (pos / SECTOR + 1) * SECTOR
                continue
            }
            if (pos + len > raw.size || len < 34) break
            val nameLen = raw[pos + 32].toInt() and 0xFF
            val flags = raw[pos + 25].toInt() and 0xFF
            val nameBytes = raw.copyOfRange(pos + 33, pos + 33 + nameLen)
            val special = nameLen == 1 && (nameBytes[0].toInt() == 0 || nameBytes[0].toInt() == 1)
            if (!special) {
                val rockRidge = if (joliet) null else rockRidgeName(raw, pos, len, nameLen)
                val name = rockRidge ?: if (joliet) String(nameBytes, Charsets.UTF_16BE) else String(nameBytes, Charsets.ISO_8859_1)
                out += IsoEntry(
                    name = if (rockRidge != null) rockRidge else name.substringBefore(';').let { if (!joliet && it.endsWith(".")) it.dropLast(1) else it },
                    isDirectory = flags and 0x02 != 0,
                    extentLba = u32(raw, pos + 2),
                    size = u32(raw, pos + 10),
                    multiExtent = flags and 0x80 != 0,
                )
            }
            pos += len
        }
        return out
    }

    /**
     * Nome longo Rock Ridge (entrada "NM" da área System Use, padrão SUSP/RRIP).
     * Muitas ISOs Linux só têm nomes completos ali (o ISO9660 puro abrevia, ex.: ".disk" → "_DISK").
     */
    private fun rockRidgeName(raw: ByteArray, pos: Int, len: Int, nameLen: Int): String? {
        var p = pos + 33 + nameLen + (if (nameLen % 2 == 0) 1 else 0)
        val end = pos + len
        val sb = StringBuilder()
        var found = false
        while (p + 4 <= end) {
            val sig = String(raw, p, 2, Charsets.US_ASCII)
            val l = raw[p + 2].toInt() and 0xFF
            if (l < 4 || p + l > end) break
            if (sig == "NM" && l >= 5) {
                val flags = raw[p + 4].toInt() and 0xFF
                if (flags and 0x06 == 0) { sb.append(String(raw, p + 5, l - 5, Charsets.UTF_8)); found = true }
            }
            p += l
        }
        return if (found && sb.isNotEmpty()) sb.toString() else null
    }

    companion object {
        const val SECTOR = 2048

        /** Abre o ISO9660 (prefere Joliet). Retorna null se não houver ISO9660. */
        fun open(reader: RandomReader): IsoFileSystem? {
            var primaryRoot: IsoEntry? = null
            var jolietRoot: IsoEntry? = null
            for (i in 16 until 16 + 64) {
                if ((i + 1L) * SECTOR > reader.size) break
                val d = reader.readFully(i.toLong() * SECTOR, SECTOR)
                if (String(d, 1, 5, Charsets.US_ASCII) != "CD001") break
                when (d[0].toInt() and 0xFF) {
                    1 -> primaryRoot = rootRecord(d)
                    2 -> if (d[88].toInt() == 0x25 && d[89].toInt() == 0x2F && d[90].toInt() in listOf(0x40, 0x43, 0x45)) jolietRoot = rootRecord(d)
                    255 -> break
                }
            }
            return when {
                jolietRoot != null -> IsoFileSystem(reader, true, jolietRoot)
                primaryRoot != null -> IsoFileSystem(reader, false, primaryRoot)
                else -> null
            }
        }

        private fun rootRecord(d: ByteArray) = IsoEntry("/", true, u32(d, 156 + 2), u32(d, 156 + 10), false)

        internal fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        internal fun u32(b: ByteArray, o: Int) = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)
    }
}
