package dev.bootforge.fs.exfat

import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Codificação/decodificação das entradas de diretório exFAT (especificação, seções 6 e 7).
 *
 * Um arquivo ou pasta ocupa um "conjunto de entradas" (entry set) de 32 bytes cada:
 *  - 0x85 File               : atributos, datas, checksum do conjunto
 *  - 0xC0 Stream Extension   : tamanho, primeiro cluster, flag NoFatChain, NameHash
 *  - 0xC1 File Name (1..17)  : até 15 caracteres UTF-16 por entrada
 */
internal object ExFatEntries {

    const val TYPE_END = 0x00
    const val TYPE_BITMAP = 0x81
    const val TYPE_UPCASE = 0x82
    const val TYPE_LABEL = 0x83
    const val TYPE_FILE = 0x85
    const val TYPE_STREAM = 0xC0
    const val TYPE_NAME = 0xC1
    const val IN_USE = 0x80

    const val ATTR_READ_ONLY = 0x01
    const val ATTR_DIRECTORY = 0x10
    const val ATTR_ARCHIVE = 0x20

    const val FLAG_ALLOCATION_POSSIBLE = 0x01
    const val FLAG_NO_FAT_CHAIN = 0x02

    const val MAX_NAME = 255
    private const val INVALID_CHARS = "\"*/:<>?\\|"

    /** Valida um nome de arquivo conforme a especificação (seção 7.7.3). */
    fun validateName(name: String) {
        if (name.isEmpty()) throw IOException("Nome vazio")
        if (name.length > MAX_NAME) throw IOException("Nome com mais de $MAX_NAME caracteres")
        if (name == "." || name == "..") throw IOException("Nome reservado: $name")
        name.firstOrNull { it.code < 0x20 || it in INVALID_CHARS }?.let {
            throw IOException("Caractere não permitido no nome: '${if (it.code < 0x20) "\\u%04x".format(it.code) else it}'")
        }
        if (name.endsWith(" ") || name.endsWith(".")) throw IOException("O nome não pode terminar com espaço ou ponto")
    }

    fun entryCountFor(name: String) = 2 + (name.length + 14) / 15

    /** Metadados que vão no conjunto de entradas. */
    data class SetData(
        val name: String,
        val attributes: Int,
        val firstCluster: Long,
        val dataLength: Long,
        val validDataLength: Long,
        val noFatChain: Boolean,
        val created: Long,
        val modified: Long,
        val accessed: Long,
    )

    /** Monta o conjunto de entradas com SetChecksum e NameHash calculados. */
    fun encodeSet(d: SetData, upcase: UpcaseTable): ByteArray {
        val count = entryCountFor(d.name)
        val b = ByteArray(count * 32)
        // --- File
        b[0] = TYPE_FILE.toByte()
        b[1] = (count - 1).toByte()
        put16(b, 4, d.attributes)
        putTimestamp(b, 8, 20, 22, d.created)
        putTimestamp(b, 12, 21, 23, d.modified)
        putTimestamp(b, 16, -1, 24, d.accessed)
        // --- Stream Extension
        val s = 32
        b[s] = TYPE_STREAM.toByte()
        // arquivo vazio não pode ter NoFatChain (exigência verificada pelo fsck.exfat)
        b[s + 1] = (FLAG_ALLOCATION_POSSIBLE or (if (d.noFatChain && d.dataLength > 0) FLAG_NO_FAT_CHAIN else 0)).toByte()
        b[s + 3] = d.name.length.toByte()
        put16(b, s + 4, nameHash(d.name, upcase))
        put64(b, s + 8, d.validDataLength)
        put32(b, s + 20, if (d.dataLength == 0L) 0 else d.firstCluster)
        put64(b, s + 24, d.dataLength)
        // --- File Name
        val nameUnits = d.name.toCharArray()
        for (i in 2 until count) {
            val o = i * 32
            b[o] = TYPE_NAME.toByte()
            for (k in 0 until 15) {
                val idx = (i - 2) * 15 + k
                if (idx >= nameUnits.size) break
                put16(b, o + 2 + k * 2, nameUnits[idx].code)
            }
        }
        put16(b, 2, setChecksum(b))
        return b
    }

    /** SetChecksum (especificação 6.3.3): todos os bytes do conjunto, menos os bytes 2–3. */
    fun setChecksum(set: ByteArray): Int {
        var sum = 0
        for (i in set.indices) {
            if (i == 2 || i == 3) continue
            sum = (((sum shl 15) or (sum ushr 1)) + (set[i].toInt() and 0xFF)) and 0xFFFF
        }
        return sum
    }

    /** NameHash (especificação 7.6.4): sobre o nome convertido pela tabela up-case. */
    fun nameHash(name: String, upcase: UpcaseTable): Int {
        var hash = 0
        for (c in name) {
            val u = upcase.upcase(c).code
            hash = (((hash shl 15) or (hash ushr 1)) + (u and 0xFF)) and 0xFFFF
            hash = (((hash shl 15) or (hash ushr 1)) + (u ushr 8)) and 0xFFFF
        }
        return hash
    }

    // ------------------------------------------------------------ datas

    /** Grava timestamp DOS + incremento de 10 ms + deslocamento UTC (seção 7.4.8). */
    private fun putTimestamp(b: ByteArray, off: Int, incOff: Int, utcOff: Int, millis: Long) {
        val z = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), ZoneId.systemDefault())
        val year = (z.year - 1980).coerceIn(0, 127)
        val v = (year.toLong() shl 25) or (z.monthValue.toLong() shl 21) or (z.dayOfMonth.toLong() shl 16) or
            (z.hour.toLong() shl 11) or (z.minute.toLong() shl 5) or (z.second / 2).toLong()
        put32(b, off, v)
        if (incOff >= 0) b[incOff] = ((z.second % 2) * 100 + z.nano / 10_000_000).toByte()
        val quarterHours = z.offset.totalSeconds / 900
        b[utcOff] = (0x80 or (quarterHours and 0x7F)).toByte()
    }

    /** Lê timestamp (com deslocamento UTC, se válido) → milissegundos UTC. */
    fun readTimestamp(b: ByteArray, off: Int, incOff: Int, utcOff: Int): Long? {
        val v = u32(b, off)
        if (v == 0L) return null
        val inc = if (incOff >= 0) (b[incOff].toInt() and 0xFF) else 0
        return runCatching {
            val local = LocalDateTime.of(
                1980 + ((v ushr 25) and 0x7F).toInt(), ((v ushr 21) and 0x0F).toInt(), ((v ushr 16) and 0x1F).toInt(),
                ((v ushr 11) and 0x1F).toInt(), ((v ushr 5) and 0x3F).toInt(), ((v and 0x1F) * 2).toInt() + inc / 100,
                (inc % 100) * 10_000_000,
            )
            val utc = b[utcOff].toInt() and 0xFF
            val offsetSeconds = if (utc and 0x80 != 0) ((utc shl 25) shr 25) * 900 else null
            if (offsetSeconds != null) local.toInstant(java.time.ZoneOffset.ofTotalSeconds(offsetSeconds)).toEpochMilli()
            else local.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

    // ------------------------------------------------------------ inteiros LE
    fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    fun u32(b: ByteArray, o: Int) = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)
    fun u64(b: ByteArray, o: Int) = u32(b, o) or (u32(b, o + 4) shl 32)
    fun put16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte() }
    fun put32(b: ByteArray, o: Int, v: Long) { for (i in 0 until 4) b[o + i] = (v ushr (8 * i)).toByte() }
    fun put64(b: ByteArray, o: Int, v: Long) { for (i in 0 until 8) b[o + i] = (v ushr (8 * i)).toByte() }
}
