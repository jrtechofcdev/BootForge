package dev.bootforge.wim

import dev.bootforge.blockdev.RandomReader
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.Collections

/**
 * Divide um arquivo WIM (ex.: `sources/install.wim` do Windows) em partes `.swm` menores que o
 * limite do FAT32 (4 GiB − 1 byte) — o método documentado pela Microsoft para pendrives FAT32
 * (`DISM /Split-Image`) e o mesmo formato gerado pelo `wimlib-imagex split`.
 *
 * Os dados compactados NÃO são recompactados: cada recurso é copiado byte a byte para uma das
 * partes; só o cabeçalho, a tabela de blobs e o XML de cada parte são gerados.
 *
 * Formato (documentação do wimlib / "Windows Imaging File Format"):
 *  - cabeçalho de 208 bytes ("MSWIM\0\0\0"), com GUID, número da parte e total de partes;
 *  - recursos (blobs) e, na parte 1, os recursos de metadados das imagens;
 *  - tabela de blobs (entradas de 50 bytes: cabeçalho de recurso, nº da parte, refcount, SHA-1);
 *  - dados XML (UTF-16LE) — copiados em todas as partes.
 */
object WimSplitter {

    /** Maior arquivo possível em FAT32. */
    const val FAT32_MAX = 0xFFFFFFFFL

    private const val HEADER_SIZE = 208
    private const val ENTRY_SIZE = 50
    private const val RES_FLAG_METADATA = 0x02
    private const val RES_FLAG_SPANNED = 0x08
    private const val RES_FLAG_SOLID = 0x10
    private const val HDR_FLAG_SPANNED = 0x08
    private const val HDR_FLAG_WRITE_IN_PROGRESS = 0x40

    /** Cabeçalho de recurso (24 bytes). */
    data class ResHdr(val sizeInWim: Long, val flags: Int, val offset: Long, val originalSize: Long) {
        fun encode(): ByteArray {
            val b = ByteArray(24)
            for (i in 0 until 7) b[i] = (sizeInWim ushr (8 * i)).toByte()
            b[7] = flags.toByte()
            put64(b, 8, offset); put64(b, 16, originalSize)
            return b
        }
        companion object {
            fun decode(b: ByteArray, o: Int): ResHdr {
                var size = 0L
                for (i in 0 until 7) size = size or ((b[o + i].toLong() and 0xFF) shl (8 * i))
                return ResHdr(size, b[o + 7].toInt() and 0xFF, u64(b, o + 8), u64(b, o + 16))
            }
        }
    }

    data class Blob(val hdr: ResHdr, val refCount: Long, val sha1: ByteArray) {
        val isMetadata get() = hdr.flags and RES_FLAG_METADATA != 0
    }

    /** Uma parte .swm: nome, tamanho final e como montar o conteúdo. */
    class Part internal constructor(val fileName: String, val number: Int, val size: Long, internal val segments: List<Segment>) {
        /** Abre o conteúdo desta parte (lido do WIM original sob demanda). */
        fun open(source: RandomReader): InputStream =
            SequenceInputStream(Collections.enumeration(segments.map { it.open(source) }))
    }

    internal sealed interface Segment {
        val length: Long
        fun open(source: RandomReader): InputStream
    }

    internal class Bytes(val data: ByteArray) : Segment {
        override val length get() = data.size.toLong()
        override fun open(source: RandomReader): InputStream = data.inputStream()
    }

    internal class Copy(val offset: Long, override val length: Long) : Segment {
        override fun open(source: RandomReader): InputStream = object : InputStream() {
            var pos = 0L
            override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= length) return -1
                val n = source.read(offset + pos, b, off, minOf(len.toLong(), length - pos).toInt())
                if (n <= 0) throw IOException("WIM terminou antes do esperado")
                pos += n
                return n
            }
        }
    }

    /** Informações básicas de um WIM. */
    data class Info(val version: Long, val flags: Long, val imageCount: Long, val partNumber: Int, val totalParts: Int, val blobs: Int, val solid: Boolean)

    fun info(src: RandomReader): Info {
        val h = header(src)
        val blobs = readBlobs(src, ResHdr.decode(h, 48))
        return Info(u32(h, 12), u32(h, 16), u32(h, 44), u16(h, 40), u16(h, 42), blobs.size, blobs.any { it.hdr.flags and RES_FLAG_SOLID != 0 })
    }

    /**
     * Planeja a divisão. Nada é gravado aqui: o resultado diz o nome e o tamanho de cada parte,
     * e [Part.open] gera o conteúdo quando for gravar (ex.: direto no pendrive).
     * @param baseName "install" → install.swm, install2.swm, install3.swm… (convenção do DISM)
     */
    fun plan(src: RandomReader, maxPartBytes: Long = FAT32_MAX, baseName: String = "install"): List<Part> {
        val h = header(src)
        val totalPartsIn = u16(h, 42)
        if (totalPartsIn != 1) throw IOException("O WIM já está dividido em $totalPartsIn partes")
        val blobs = readBlobs(src, ResHdr.decode(h, 48))
        if (blobs.any { it.hdr.flags and (RES_FLAG_SOLID or RES_FLAG_SPANNED) != 0 }) {
            throw IOException("WIM com recursos \"sólidos\" (LZMS/ESD) não pode ser dividido por cópia direta")
        }
        val xmlHdr = ResHdr.decode(h, 72)
        val xml = src.readFully(xmlHdr.offset, xmlHdr.sizeInWim.toInt())
        val bootMeta = ResHdr.decode(h, 96)

        // Metadados primeiro (parte 1), depois os demais na ordem original do arquivo.
        val ordered = blobs.filter { it.isMetadata }.sortedBy { it.hdr.offset } + blobs.filter { !it.isMetadata }.sortedBy { it.hdr.offset }
        val groups = mutableListOf<MutableList<Blob>>(mutableListOf())
        var current = HEADER_SIZE.toLong() + xml.size
        for (b in ordered) {
            val cost = b.hdr.sizeInWim + ENTRY_SIZE
            if (cost + HEADER_SIZE + xml.size > maxPartBytes) throw IOException("Um único recurso (${b.hdr.sizeInWim} bytes) excede o limite da parte")
            val canMove = groups.last().isNotEmpty() && !(b.isMetadata) // metadados ficam todos na parte 1
            if (current + cost > maxPartBytes && canMove) {
                groups += mutableListOf<Blob>()
                current = HEADER_SIZE.toLong() + xml.size
            }
            groups.last() += b
            current += cost
        }
        if (groups.first().any { !it.isMetadata } && groups.first().count { it.isMetadata } != blobs.count { it.isMetadata }) {
            throw IOException("Metadados não couberam na primeira parte")
        }
        val total = groups.size
        if (total > 0xFFFF) throw IOException("Partes demais")

        return groups.mapIndexed { idx, group ->
            val number = idx + 1
            val segments = mutableListOf<Segment>()
            var offset = HEADER_SIZE.toLong()
            val table = ByteArray(group.size * ENTRY_SIZE)
            var newBoot = ResHdr(0, 0, 0, 0)
            group.forEachIndexed { i, b ->
                segments += Copy(b.hdr.offset, b.hdr.sizeInWim)
                val nh = b.hdr.copy(offset = offset)
                if (b.isMetadata && bootMeta.sizeInWim > 0 && b.hdr.offset == bootMeta.offset) newBoot = bootMeta.copy(offset = offset)
                nh.encode().copyInto(table, i * ENTRY_SIZE)
                put16(table, i * ENTRY_SIZE + 24, number)
                put32(table, i * ENTRY_SIZE + 26, b.refCount)
                b.sha1.copyInto(table, i * ENTRY_SIZE + 30)
                offset += b.hdr.sizeInWim
            }
            val tableHdr = ResHdr(table.size.toLong(), RES_FLAG_METADATA, offset, table.size.toLong())
            offset += table.size
            val xmlOut = ResHdr(xml.size.toLong(), RES_FLAG_METADATA, offset, xml.size.toLong())
            offset += xml.size
            val nh = h.copyOf()
            var flags = u32(h, 16) and HDR_FLAG_WRITE_IN_PROGRESS.toLong().inv()
            if (total > 1) flags = flags or HDR_FLAG_SPANNED.toLong()
            put32(nh, 16, flags)
            put16(nh, 40, number)
            put16(nh, 42, total)
            tableHdr.encode().copyInto(nh, 48)
            xmlOut.encode().copyInto(nh, 72)
            (if (number == 1) newBoot else ResHdr(0, 0, 0, 0)).encode().copyInto(nh, 96)
            ResHdr(0, 0, 0, 0).encode().copyInto(nh, 124) // tabela de integridade não é gerada
            val all = listOf<Segment>(Bytes(nh)) + segments + listOf(Bytes(table), Bytes(xml))
            val name = if (number == 1) "$baseName.swm" else "$baseName$number.swm"
            Part(name, number, all.sumOf { it.length }, all)
        }
    }

    private fun header(src: RandomReader): ByteArray {
        val h = src.readFully(0, HEADER_SIZE)
        if (String(h, 0, 8, Charsets.ISO_8859_1) != "MSWIM\u0000\u0000\u0000") throw IOException("Não é um arquivo WIM")
        if (u32(h, 8) != HEADER_SIZE.toLong()) throw IOException("Cabeçalho WIM com tamanho inesperado")
        return h
    }

    private fun readBlobs(src: RandomReader, table: ResHdr): List<Blob> {
        if (table.flags and 0x04 != 0) throw IOException("Tabela de blobs compactada não suportada")
        if (table.sizeInWim > 512L shl 20) throw IOException("Tabela de blobs grande demais")
        val raw = src.readFully(table.offset, table.sizeInWim.toInt())
        return (0 until raw.size / ENTRY_SIZE).map { i ->
            val o = i * ENTRY_SIZE
            Blob(ResHdr.decode(raw, o), u32(raw, o + 26), raw.copyOfRange(o + 30, o + 50))
        }
    }

    internal fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    internal fun u32(b: ByteArray, o: Int) = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)
    internal fun u64(b: ByteArray, o: Int) = u32(b, o) or (u32(b, o + 4) shl 32)
    internal fun put16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte() }
    internal fun put32(b: ByteArray, o: Int, v: Long) { for (i in 0 until 4) b[o + i] = (v ushr (8 * i)).toByte() }
    internal fun put64(b: ByteArray, o: Int, v: Long) { for (i in 0 until 8) b[o + i] = (v ushr (8 * i)).toByte() }
}
