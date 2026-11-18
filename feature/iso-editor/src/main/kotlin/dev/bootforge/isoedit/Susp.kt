package dev.bootforge.isoedit

import java.time.ZonedDateTime

/**
 * Entrada SUSP (System Use Sharing Protocol, IEEE P1281) — o "envelope" das extensões
 * Rock Ridge (RRIP, IEEE P1282) dentro dos registros de diretório ISO9660.
 * Formato: assinatura (2 bytes), tamanho total (1), versão (1), dados.
 */
class SuspEntry(val sig: String, val version: Int, val data: ByteArray) {
    val length: Int get() = 4 + data.size

    fun encodeInto(b: ByteArray, off: Int) {
        b[off] = sig[0].code.toByte(); b[off + 1] = sig[1].code.toByte()
        b[off + 2] = length.toByte(); b[off + 3] = version.toByte()
        data.copyInto(b, off + 4)
    }

    override fun toString() = "$sig(${data.size})"
}

object Susp {

    /** Lê as entradas de uma área System Use (sem seguir CE). */
    fun parse(b: ByteArray, from: Int, to: Int): List<SuspEntry> {
        val out = mutableListOf<SuspEntry>()
        var p = from
        while (p + 4 <= to) {
            val len = b[p + 2].toInt() and 0xFF
            if (len < 4 || p + len > to) break
            val sig = String(b, p, 2, Charsets.ISO_8859_1)
            if (!sig[0].isLetterOrDigit() || !sig[1].isLetterOrDigit()) break // preenchimento
            out += SuspEntry(sig, b[p + 3].toInt() and 0xFF, b.copyOfRange(p + 4, p + len))
            if (sig == "ST") break
            p += len
        }
        return out
    }

    /** Dados de uma entrada CE: (bloco, deslocamento, tamanho). */
    fun ce(e: SuspEntry): Triple<Long, Int, Int> =
        Triple(u32le(e.data, 0), u32le(e.data, 8).toInt(), u32le(e.data, 16).toInt())

    /** NM (nome alternativo). Nomes longos podem vir em vários NM com flag CONTINUE (bit 0). */
    fun nm(name: String): List<SuspEntry> {
        val bytes = name.toByteArray(Charsets.UTF_8)
        val out = mutableListOf<SuspEntry>()
        var p = 0
        do {
            val n = minOf(250, bytes.size - p)
            val more = p + n < bytes.size
            out += SuspEntry("NM", 1, byteArrayOf((if (more) 1 else 0).toByte()) + bytes.copyOfRange(p, p + n))
            p += n
        } while (p < bytes.size)
        return out
    }

    /** PX (permissões POSIX). [withIno] = RRIP 1.12 (44 bytes) em vez de 1.10 (36 bytes). */
    fun px(mode: Int, links: Int, ino: Long, withIno: Boolean): SuspEntry {
        val d = ByteArray(if (withIno) 40 else 32)
        bb(d, 0, mode.toLong()); bb(d, 8, links.toLong()); bb(d, 16, 0); bb(d, 24, 0)
        if (withIno) bb(d, 32, ino)
        return SuspEntry("PX", 1, d)
    }

    /** TF (datas): modificação, acesso e atributos, formato curto de 7 bytes. */
    fun tf(t: ZonedDateTime): SuspEntry {
        val one = shortDate(t)
        return SuspEntry("TF", 1, byteArrayOf(0x0E) + one + one + one)
    }

    fun sp(skip: Int = 0) = SuspEntry("SP", 1, byteArrayOf(0xBE.toByte(), 0xEF.toByte(), skip.toByte()))

    /** ER padrão do RRIP 1.10 (o mesmo do genisoimage/xorriso). */
    fun erRrip1991A(): SuspEntry {
        val id = "RRIP_1991A".toByteArray()
        val des = "THE ROCK RIDGE INTERCHANGE PROTOCOL PROVIDES SUPPORT FOR POSIX FILE SYSTEM SEMANTICS".toByteArray()
        val src = "PLEASE CONTACT DISC PUBLISHER FOR SPECIFICATION SOURCE.  SEE PUBLISHER IDENTIFIER IN PRIMARY VOLUME DESCRIPTOR FOR CONTACT INFORMATION.".toByteArray()
        return SuspEntry("ER", 1, byteArrayOf(id.size.toByte(), des.size.toByte(), src.size.toByte(), 1) + id + des + src)
    }

    fun ceEntry(block: Long, offset: Int, length: Int): SuspEntry {
        val d = ByteArray(24); bb(d, 0, block); bb(d, 8, offset.toLong()); bb(d, 16, length.toLong())
        return SuspEntry("CE", 1, d)
    }

    /** Data curta ISO9660 (7 bytes): ano-1900, mês, dia, hora, min, seg, fuso (15 min). */
    fun shortDate(t: ZonedDateTime): ByteArray = byteArrayOf(
        (t.year - 1900).toByte(), t.monthValue.toByte(), t.dayOfMonth.toByte(),
        t.hour.toByte(), t.minute.toByte(), t.second.toByte(), (t.offset.totalSeconds / 900).toByte(),
    )

    /** Inteiro "both-endian" (LE + BE) de 32 bits em 8 bytes. */
    fun bb(b: ByteArray, o: Int, v: Long) {
        for (i in 0 until 4) { b[o + i] = (v ushr (8 * i)).toByte(); b[o + 7 - i] = (v ushr (8 * i)).toByte() }
    }

    fun bb16(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
    }

    fun u32le(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    fun u16le(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    fun put32le(b: ByteArray, o: Int, v: Long) { for (i in 0 until 4) b[o + i] = (v ushr (8 * i)).toByte() }
    fun put32be(b: ByteArray, o: Int, v: Long) { for (i in 0 until 4) b[o + 3 - i] = (v ushr (8 * i)).toByte() }
    fun put16le(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte() }
    fun put16be(b: ByteArray, o: Int, v: Int) { b[o] = (v ushr 8).toByte(); b[o + 1] = v.toByte() }
}
