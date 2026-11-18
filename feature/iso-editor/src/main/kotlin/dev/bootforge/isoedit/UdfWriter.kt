package dev.bootforge.isoedit

import java.time.ZonedDateTime
import java.util.IdentityHashMap

/**
 * Gravador UDF 1.02 (ECMA-167 + OSTA UDF) no formato "ponte" ISO9660/UDF, o mesmo das ISOs
 * do Windows (oscdimg) e do `mkisofs -udf`: os dados dos arquivos são COMPARTILHADOS com o
 * ISO9660; o UDF só acrescenta os próprios descritores.
 *
 * Mapa (setores absolutos de 2048 bytes):
 *  - BEA01/NSR02/TEA01 logo após o terminador ISO9660 (sequência de reconhecimento)
 *  - 32–37 sequência principal de descritores (PVD, IUVD, PD, LVD, USD, TD); 48–53 cópia reserva
 *  - 64–65 descritor de integridade (LVID) + terminador
 *  - 256 âncora (AVDP) · último setor: âncora de backup
 *  - partição: do setor 257 até o penúltimo; File Set Descriptor, File Entries e pastas
 */
internal class UdfWriter(private val root: VDir, private val now: ZonedDateTime, private val volumeId: String) {

    private inner class D(val dir: VDir, val parent: D?) {
        var fe = 0L; var data = 0L; var bytes = 0L; var uid = 0L
        val subdirs = mutableListOf<D>()
    }

    private val dirs = mutableListOf<D>()
    private val fileFe = IdentityHashMap<VFile, Pair<Long, Long>>() // FE (LBA absoluto) e unique id
    private var fsd = 0L
    private var nextUid = 16L

    fun assign(alloc: SectorAllocator) {
        fsd = alloc.alloc(2) // FSD + terminador
        fun rec(d: VDir, parent: D?): D {
            val info = D(d, parent)
            dirs += info
            info.bytes = fidLen(0) + d.children.sumOf { fidLen(dchars(it.name).size) }
            info.uid = if (parent == null) 0 else nextUid++
            info.fe = alloc.alloc(1)
            info.data = alloc.allocBytes(info.bytes)
            for (c in d.children) {
                when (c) {
                    is VDir -> info.subdirs += rec(c, info)
                    is VFile -> fileFe[c] = alloc.alloc(1) to nextUid++
                }
            }
            return info
        }
        rec(root, null)
    }

    fun encode(extents: IdentityHashMap<VFile, FileExtents>, totalSectors: Long): List<BytesPiece> {
        val out = mutableListOf<BytesPiece>()
        val partLen = totalSectors - 1 - PARTITION_START
        fun rel(abs: Long) = abs - PARTITION_START
        val ts = timestamp(now)

        // ---- sequência de descritores (principal e reserva)
        for ((base, _) in listOf(MAIN_VDS to 0, RESERVE_VDS to 1)) {
            out += BytesPiece(base * 2048, sector(pvd(base, ts)))
            out += BytesPiece((base + 1) * 2048, sector(iuvd(base + 1)))
            out += BytesPiece((base + 2) * 2048, sector(pd(base + 2, partLen)))
            out += BytesPiece((base + 3) * 2048, sector(lvd(base + 3, rel(fsd))))
            out += BytesPiece((base + 4) * 2048, sector(usd(base + 4)))
            out += BytesPiece((base + 5) * 2048, sector(tagged(8, ByteArray(512), base + 5)))
        }
        val files = fileFe.size.toLong()
        out += BytesPiece(LVID * 2048, sector(lvid(ts, partLen, files, dirs.size.toLong())))
        out += BytesPiece((LVID + 1) * 2048, sector(tagged(8, ByteArray(512), LVID + 1)))
        val avdp = { loc: Long -> sector(anchor(loc)) }
        out += BytesPiece(ANCHOR * 2048, avdp(ANCHOR))
        out += BytesPiece((totalSectors - 1) * 2048, avdp(totalSectors - 1))

        // ---- partição: File Set Descriptor
        out += BytesPiece(fsd * 2048, sector(fileSet(rel(fsd), rel(dirs.first().fe), ts)))
        out += BytesPiece((fsd + 1) * 2048, sector(tagged(8, ByteArray(512), rel(fsd + 1))))

        // ---- pastas: File Entry + identificadores (FIDs)
        for (d in dirs) {
            val data = ByteArray(((d.bytes + 2047) / 2048 * 2048).toInt())
            var pos = 0
            val parent = d.parent ?: d
            pos += fid(data, pos, rel(d.data), "", 0x0A, rel(parent.fe), parent.uid)
            val sub = d.subdirs.iterator()
            for (c in d.dir.children) {
                val hidden = if (c.meta.hidden) 0x01 else 0
                when (c) {
                    is VDir -> { val s = sub.next(); pos += fid(data, pos, rel(d.data), c.name, 0x02 or hidden, rel(s.fe), s.uid) }
                    is VFile -> { val (fe, uid) = fileFe.getValue(c); pos += fid(data, pos, rel(d.data), c.name, hidden, rel(fe), uid) }
                }
            }
            out += BytesPiece(d.data * 2048, data)
            val links = 1 + d.subdirs.size
            out += BytesPiece(d.fe * 2048, sector(fileEntry(rel(d.fe), 4, d.bytes, listOf(rel(d.data) to d.bytes), links, d.uid, ts)))
        }
        // ---- arquivos: File Entry com os trechos (short_ad, até 1 GiB cada)
        for ((f, v) in fileFe) {
            val (fe, uid) = v
            val ads = mutableListOf<Pair<Long, Long>>()
            if (f.size > 0) {
                val first = extents.getValue(f).first().first
                var left = f.size; var lba = first
                while (left > 0) { val n = minOf(left, MAX_AD); ads += rel(lba) to n; lba += (n + 2047) / 2048; left -= n }
            }
            out += BytesPiece(fe * 2048, sector(fileEntry(rel(fe), 5, f.size, ads, 1, uid, ts)))
        }
        return out
    }

    // ------------------------------------------------------------------ descritores

    private fun pvd(loc: Long, ts: ByteArray): ByteArray {
        val b = ByteArray(512)
        Susp.put32le(b, 16, 0); Susp.put32le(b, 20, 0)
        dstring(volumeId.take(30), 32).copyInto(b, 24)
        Susp.put16le(b, 56, 1); Susp.put16le(b, 58, 1); Susp.put16le(b, 60, 2); Susp.put16le(b, 62, 2)
        Susp.put32le(b, 64, 1); Susp.put32le(b, 68, 1)
        dstring(String.format("%08X%s", (now.toEpochSecond() and 0xFFFFFFFFL), " BootForge"), 128).copyInto(b, 72)
        charspec().copyInto(b, 200); charspec().copyInto(b, 264)
        ts.copyInto(b, 376)
        regid("*BootForge", byteArrayOf()).copyInto(b, 388)
        return tagged(1, b, loc)
    }

    private fun iuvd(loc: Long): ByteArray {
        val b = ByteArray(512)
        Susp.put32le(b, 16, 1)
        regid("*UDF LV Info", udfSuffix()).copyInto(b, 20)
        charspec().copyInto(b, 52)
        dstring(volumeId, 128).copyInto(b, 116)
        regid("*BootForge", byteArrayOf()).copyInto(b, 352)
        return tagged(4, b, loc)
    }

    private fun pd(loc: Long, partLen: Long): ByteArray {
        val b = ByteArray(512)
        Susp.put32le(b, 16, 2)
        Susp.put16le(b, 20, 1); Susp.put16le(b, 22, 0)
        regid("+NSR02", byteArrayOf()).copyInto(b, 24)
        Susp.put32le(b, 184, 1) // somente leitura
        Susp.put32le(b, 188, PARTITION_START); Susp.put32le(b, 192, partLen)
        regid("*BootForge", byteArrayOf()).copyInto(b, 196)
        return tagged(5, b, loc)
    }

    private fun lvd(loc: Long, fsdRel: Long): ByteArray {
        val b = ByteArray(446)
        Susp.put32le(b, 16, 3)
        charspec().copyInto(b, 20)
        dstring(volumeId, 128).copyInto(b, 84)
        Susp.put32le(b, 212, 2048)
        regid("*OSTA UDF Compliant", udfSuffix()).copyInto(b, 216)
        longAd(2048, fsdRel).copyInto(b, 248)
        Susp.put32le(b, 264, 6); Susp.put32le(b, 268, 1)
        regid("*BootForge", byteArrayOf()).copyInto(b, 272)
        Susp.put32le(b, 432, 2 * 2048); Susp.put32le(b, 436, LVID)
        b[440] = 1; b[441] = 6; Susp.put16le(b, 442, 1); Susp.put16le(b, 444, 0)
        return tagged(6, b, loc)
    }

    private fun usd(loc: Long): ByteArray {
        val b = ByteArray(24)
        Susp.put32le(b, 16, 4)
        return tagged(7, b, loc)
    }

    private fun lvid(ts: ByteArray, partLen: Long, files: Long, dirCount: Long): ByteArray {
        val b = ByteArray(134)
        ts.copyInto(b, 16)
        Susp.put32le(b, 28, 1) // fechado
        ElTorito.put64(b, 40, nextUid)
        Susp.put32le(b, 72, 1); Susp.put32le(b, 76, 46)
        Susp.put32le(b, 80, 0); Susp.put32le(b, 84, partLen)
        regid("*BootForge", byteArrayOf()).copyInto(b, 88)
        Susp.put32le(b, 120, files); Susp.put32le(b, 124, dirCount)
        Susp.put16le(b, 128, 0x0102); Susp.put16le(b, 130, 0x0102); Susp.put16le(b, 132, 0x0102)
        return tagged(9, b, LVID)
    }

    private fun anchor(loc: Long): ByteArray {
        val b = ByteArray(512)
        Susp.put32le(b, 16, 16L * 2048); Susp.put32le(b, 20, MAIN_VDS)
        Susp.put32le(b, 24, 16L * 2048); Susp.put32le(b, 28, RESERVE_VDS)
        return tagged(2, b, loc)
    }

    private fun fileSet(loc: Long, rootFeRel: Long, ts: ByteArray): ByteArray {
        val b = ByteArray(512)
        ts.copyInto(b, 16)
        Susp.put16le(b, 28, 3); Susp.put16le(b, 30, 3); Susp.put32le(b, 32, 1); Susp.put32le(b, 36, 1)
        charspec().copyInto(b, 48)
        dstring(volumeId, 128).copyInto(b, 112)
        charspec().copyInto(b, 240)
        dstring(volumeId.take(30), 32).copyInto(b, 304)
        longAd(2048, rootFeRel).copyInto(b, 400)
        regid("*OSTA UDF Compliant", udfSuffix()).copyInto(b, 416)
        return tagged(256, b, loc)
    }

    private fun fileEntry(loc: Long, type: Int, size: Long, ads: List<Pair<Long, Long>>, links: Int, uid: Long, ts: ByteArray): ByteArray {
        val b = ByteArray(176 + ads.size * 8)
        // ICB tag
        Susp.put16le(b, 20, 4); Susp.put16le(b, 24, 1); b[27] = type.toByte()
        Susp.put16le(b, 34, 0) // short_ad
        Susp.put32le(b, 36, 0xFFFFFFFFL); Susp.put32le(b, 40, 0xFFFFFFFFL)
        Susp.put32le(b, 44, if (type == 4) 0x14A5 else 0x1084)
        Susp.put16le(b, 48, links)
        ElTorito.put64(b, 56, size); ElTorito.put64(b, 64, (size + 2047) / 2048)
        ts.copyInto(b, 72); ts.copyInto(b, 84); ts.copyInto(b, 96)
        Susp.put32le(b, 108, 1)
        regid("*BootForge", byteArrayOf()).copyInto(b, 128)
        ElTorito.put64(b, 160, uid)
        Susp.put32le(b, 168, 0); Susp.put32le(b, 172, ads.size * 8L)
        ads.forEachIndexed { i, (pos, len) -> Susp.put32le(b, 176 + i * 8, len); Susp.put32le(b, 180 + i * 8, pos) }
        return tagged(261, b, loc)
    }

    /** Grava um File Identifier Descriptor; devolve o tamanho (múltiplo de 4). */
    private fun fid(buf: ByteArray, pos: Int, dataRel: Long, name: String, chars: Int, feRel: Long, uid: Long): Int {
        val id = if (name.isEmpty()) ByteArray(0) else dchars(name)
        val len = fidLen(id.size).toInt()
        val b = ByteArray(len)
        Susp.put16le(b, 16, 1); b[18] = chars.toByte(); b[19] = id.size.toByte()
        longAd(2048, feRel).copyInto(b, 20)
        Susp.put32le(b, 20 + 10, uid and 0xFFFFFFFFL) // "UDF Unique ID" (impl. use do long_ad)
        Susp.put16le(b, 36, 0)
        id.copyInto(b, 38)
        tagged(257, b, dataRel + pos / 2048).copyInto(buf, pos)
        return len
    }

    private fun fidLen(idLen: Int): Long = ((38L + idLen + 3) / 4) * 4

    // ------------------------------------------------------------------ tipos básicos

    /** Coloca o descritor num setor de 2048 bytes. */
    private fun sector(d: ByteArray) = d.copyOf(2048)

    /** Preenche a "tag" de 16 bytes: id, versão 2, checksum, CRC-CCITT e localização. */
    private fun tagged(id: Int, b: ByteArray, location: Long): ByteArray {
        Susp.put16le(b, 0, id); Susp.put16le(b, 2, 2); b[4] = 0; b[5] = 0
        Susp.put16le(b, 6, 1)
        val crcLen = b.size - 16
        Susp.put16le(b, 8, crcItu(b, 16, crcLen)); Susp.put16le(b, 10, crcLen)
        Susp.put32le(b, 12, location)
        var sum = 0
        for (i in 0 until 16) if (i != 4) sum += b[i].toInt() and 0xFF
        b[4] = sum.toByte()
        return b
    }

    private fun charspec(): ByteArray = ByteArray(64).also { "OSTA Compressed Unicode".toByteArray(Charsets.US_ASCII).copyInto(it, 1) }

    private fun regid(id: String, suffix: ByteArray): ByteArray =
        ByteArray(32).also { id.toByteArray(Charsets.US_ASCII).copyInto(it, 1); suffix.copyInto(it, 24) }

    private fun udfSuffix() = byteArrayOf(0x02, 0x01, 0x00)

    private fun longAd(len: Long, lbn: Long) = ByteArray(16).also { Susp.put32le(it, 0, len); Susp.put32le(it, 4, lbn) }

    /** OSTA Compressed Unicode: 8 bits por caractere quando possível, senão 16 (UTF-16BE). */
    private fun dchars(s: String): ByteArray {
        val wide = s.any { it.code > 0xFF }
        return if (wide) byteArrayOf(16) + s.toByteArray(Charsets.UTF_16BE) else byteArrayOf(8) + s.toByteArray(Charsets.ISO_8859_1)
    }

    private fun dstring(s: String, size: Int): ByteArray {
        val b = ByteArray(size)
        if (s.isEmpty()) return b
        var d = dchars(s)
        if (d.size > size - 1) d = d.copyOf(if (d[0].toInt() == 16) ((size - 2) / 2) * 2 + 1 else size - 1)
        d.copyInto(b, 0)
        b[size - 1] = d.size.toByte()
        return b
    }

    private fun timestamp(t: ZonedDateTime): ByteArray {
        val b = ByteArray(12)
        val tz = (t.offset.totalSeconds / 60) and 0xFFF
        Susp.put16le(b, 0, 0x1000 or tz)
        Susp.put16le(b, 2, t.year)
        b[4] = t.monthValue.toByte(); b[5] = t.dayOfMonth.toByte(); b[6] = t.hour.toByte(); b[7] = t.minute.toByte(); b[8] = t.second.toByte()
        return b
    }

    companion object {
        const val MAIN_VDS = 32L
        const val RESERVE_VDS = 48L
        const val LVID = 64L
        const val ANCHOR = 256L
        const val PARTITION_START = 257L
        private const val MAX_AD = 0x3FFFF800L

        /** BEA01 / NSR02 / TEA01 (ECMA-167 parte 2). */
        fun recognitionSequence(lba: Long): List<BytesPiece> = listOf("BEA01", "NSR02", "TEA01").mapIndexed { i, id ->
            BytesPiece((lba + i) * 2048, ByteArray(2048).also { b -> id.toByteArray().copyInto(b, 1); b[6] = 1 })
        }

        /** CRC-ITU-T (CCITT, polinômio 0x1021, valor inicial 0) usado nas tags UDF. */
        fun crcItu(b: ByteArray, off: Int, len: Int): Int {
            var crc = 0
            for (i in off until off + len) {
                crc = crc xor ((b[i].toInt() and 0xFF) shl 8)
                repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
                crc = crc and 0xFFFF
            }
            return crc
        }
    }
}
