package dev.bootforge.partition

import java.util.UUID

/**
 * Leitura/escrita de inteiros little-endian em arrays de bytes.
 * Todas as estruturas de disco (MBR, GPT, FAT, exFAT) usam little-endian.
 */
object LE {
    fun u8(b: ByteArray, off: Int): Int = b[off].toInt() and 0xFF
    fun u16(b: ByteArray, off: Int): Int = u8(b, off) or (u8(b, off + 1) shl 8)
    fun u32(b: ByteArray, off: Int): Long = (u16(b, off).toLong()) or (u16(b, off + 2).toLong() shl 16)
    fun u64(b: ByteArray, off: Int): Long = u32(b, off) or (u32(b, off + 4) shl 32)

    fun put8(b: ByteArray, off: Int, v: Int) { b[off] = v.toByte() }
    fun put16(b: ByteArray, off: Int, v: Int) { b[off] = v.toByte(); b[off + 1] = (v ushr 8).toByte() }
    fun put32(b: ByteArray, off: Int, v: Long) { put16(b, off, (v and 0xFFFF).toInt()); put16(b, off + 2, ((v ushr 16) and 0xFFFF).toInt()) }
    fun put64(b: ByteArray, off: Int, v: Long) { put32(b, off, v and 0xFFFFFFFFL); put32(b, off + 4, v ushr 32) }

    /**
     * GUID no formato "misto" da Microsoft/UEFI: os 3 primeiros campos são little-endian,
     * os 8 bytes finais ficam na ordem natural.
     */
    fun guid(b: ByteArray, off: Int): UUID {
        val d1 = u32(b, off)
        val d2 = u16(b, off + 4).toLong()
        val d3 = u16(b, off + 6).toLong()
        var lsb = 0L
        for (i in 0 until 8) lsb = (lsb shl 8) or u8(b, off + 8 + i).toLong()
        val msb = (d1 shl 32) or (d2 shl 16) or d3
        return UUID(msb, lsb)
    }

    fun putGuid(b: ByteArray, off: Int, uuid: UUID) {
        val msb = uuid.mostSignificantBits
        put32(b, off, (msb ushr 32) and 0xFFFFFFFFL)
        put16(b, off + 4, ((msb ushr 16) and 0xFFFF).toInt())
        put16(b, off + 6, (msb and 0xFFFF).toInt())
        val lsb = uuid.leastSignificantBits
        for (i in 0 until 8) b[off + 8 + i] = (lsb ushr (56 - 8 * i)).toByte()
    }
}
