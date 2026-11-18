package dev.bootforge.isoedit

import dev.bootforge.blockdev.RandomReader

/**
 * Entrada do catálogo El Torito (especificação "El Torito" Phoenix/IBM 1995): diz ao firmware
 * qual imagem carregar para iniciar pelo CD/pendrive.
 */
data class BootEntry(
    /** Posição (bytes) da entrada de 32 bytes dentro do catálogo — usada para corrigir o LBA. */
    val catalogOffset: Int,
    /** 0 = BIOS (x86), 0xEF = UEFI, 1 = PowerPC, 2 = Mac. */
    val platform: Int,
    val bootable: Boolean,
    /** 0 = sem emulação; 1/2/3 = disquete 1,2/1,44/2,88 MB; 4 = disco rígido. */
    val media: Int,
    val loadSegment: Int,
    val sectorCount: Int,
    /** Setor (2048 bytes) onde começa a imagem de boot. */
    val lba: Long,
) {
    val platformName: String get() = when (platform) { 0 -> "BIOS"; 0xEF -> "UEFI"; 1 -> "PowerPC"; 2 -> "Mac"; else -> "0x%02X".format(platform) }
    val mediaName: String get() = when (media) { 0 -> "sem emulação"; 1 -> "disquete 1,2 MB"; 2 -> "disquete 1,44 MB"; 3 -> "disquete 2,88 MB"; 4 -> "disco rígido"; else -> "tipo $media" }
}

/** Catálogo El Torito lido da imagem (bytes originais + entradas interpretadas). */
class ElToritoCatalog(val lba: Long, val raw: ByteArray, val entries: List<BootEntry>) {

    /** Cópia do catálogo com novos LBAs/tamanhos (mesma estrutura, só os campos corrigidos). */
    fun patched(newLba: Map<BootEntry, Long>, newCount: Map<BootEntry, Int> = emptyMap()): ByteArray {
        val b = raw.copyOf()
        for (e in entries) {
            newLba[e]?.let { Susp.put32le(b, e.catalogOffset + 8, it) }
            newCount[e]?.let { Susp.put16le(b, e.catalogOffset + 6, it) }
        }
        return b
    }
}

object ElTorito {

    /** Lê o catálogo apontado pelo Boot Record (setor de descritor tipo 0). */
    fun parse(r: RandomReader, bootRecord: ByteArray): ElToritoCatalog? {
        if (String(bootRecord, 7, 23, Charsets.US_ASCII) != "EL TORITO SPECIFICATION") return null
        val lba = Susp.u32le(bootRecord, 0x47)
        if ((lba + 1) * 2048 > r.size) return null
        // o catálogo normalmente tem 1 setor; lemos até 4 para catálogos grandes
        val sectors = minOf(4L, r.size / 2048 - lba).toInt()
        val raw = r.readFully(lba * 2048, sectors * 2048)
        if ((raw[0].toInt() and 0xFF) != 0x01 || (raw[30].toInt() and 0xFF) != 0x55 || (raw[31].toInt() and 0xFF) != 0xAA) return null
        var sum = 0
        for (i in 0 until 32 step 2) sum += Susp.u16le(raw, i)
        if (sum and 0xFFFF != 0) return null // checksum da entrada de validação
        val entries = mutableListOf<BootEntry>()
        val defaultPlatform = raw[1].toInt() and 0xFF
        fun entry(off: Int, platform: Int) = BootEntry(
            off, platform, (raw[off].toInt() and 0xFF) == 0x88, raw[off + 1].toInt() and 0x0F,
            Susp.u16le(raw, off + 2), Susp.u16le(raw, off + 6), Susp.u32le(raw, off + 8),
        )
        entries += entry(32, defaultPlatform)
        var p = 64
        var lastUsedEnd = 64
        while (p + 32 <= raw.size) {
            val h = raw[p].toInt() and 0xFF
            if (h != 0x90 && h != 0x91) break
            val platform = raw[p + 1].toInt() and 0xFF
            val n = Susp.u16le(raw, p + 2)
            var q = p + 32
            repeat(n) {
                if (q + 32 > raw.size) return@repeat
                val ind = raw[q].toInt() and 0xFF
                if (ind == 0x88 || ind == 0x00) entries += entry(q, platform)
                q += 32
                // entradas de extensão (0x44) seguem a entrada principal
                while (q + 32 <= raw.size && (raw[q].toInt() and 0xFF) == 0x44) q += 32
            }
            p = q
            lastUsedEnd = q
            if (h == 0x91) break
        }
        val used = ((lastUsedEnd + 2047) / 2048) * 2048
        return ElToritoCatalog(lba, raw.copyOf(maxOf(2048, used)), entries.filter { it.lba > 0 })
    }

    /**
     * Tamanho real de uma imagem de boot que NÃO aparece como arquivo (ex.: efisys.bin do Windows).
     * O campo "setores" do catálogo pode indicar só o trecho carregado inicialmente, então
     * olhamos o conteúdo: disquetes têm tamanho fixo e imagens EFI são volumes FAT.
     */
    fun hiddenImageSize(r: RandomReader, e: BootEntry): Long {
        val start = e.lba * 2048
        val byCount = e.sectorCount.toLong() * 512
        val fixed = when (e.media) { 1 -> 1_228_800L; 2 -> 1_474_560L; 3 -> 2_949_120L; else -> 0L }
        if (fixed > 0) return fixed
        val head = runCatching { r.readFully(start, 512) }.getOrNull() ?: return maxOf(byCount, 2048)
        if (e.media == 4 && (head[510].toInt() and 0xFF) == 0x55) {
            val end = (0 until 4).maxOf { i -> val o = 0x1BE + i * 16; Susp.u32le(head, o + 8) + Susp.u32le(head, o + 12) }
            if (end > 0) return end * 512
        }
        val fat = (head[510].toInt() and 0xFF) == 0x55 && (head[511].toInt() and 0xFF) == 0xAA && Susp.u16le(head, 11) == 512
        if (fat) {
            val total = Susp.u16le(head, 19).toLong().takeIf { it != 0L } ?: Susp.u32le(head, 32)
            if (total * 512 > byCount && start + total * 512 <= r.size) return total * 512
        }
        return maxOf(byCount, 2048).coerceAtMost(r.size - start)
    }

    /**
     * "Boot info table" (opção -boot-info-table do mkisofs/xorriso): o isolinux.bin e o
     * eltorito.img do GRUB guardam nos bytes 8–63 onde estão gravados. Se a imagem mudar de
     * lugar, a tabela precisa ser recalculada.
     */
    fun hasBootInfoTable(data: ByteArray, lba: Long): Boolean =
        data.size >= 64 && Susp.u32le(data, 8) == 16L && Susp.u32le(data, 12) == lba && Susp.u32le(data, 16) == data.size.toLong() &&
            Susp.u32le(data, 20) == bootInfoChecksum(data)

    /** Soma de 32 bits de todas as palavras do byte 64 até o fim (campo bi_csum). */
    fun bootInfoChecksum(data: ByteArray): Long {
        var sum = 0L
        var i = 64
        while (i + 4 <= data.size) { sum = (sum + Susp.u32le(data, i)) and 0xFFFFFFFFL; i += 4 }
        return sum
    }

    fun applyBootInfoTable(data: ByteArray, lba: Long) {
        Susp.put32le(data, 8, 16)
        Susp.put32le(data, 12, lba)
        Susp.put32le(data, 16, data.size.toLong())
        Susp.put32le(data, 20, bootInfoChecksum(data))
    }

    /** Info de boot do GRUB2 ("--grub2-boot-info"): no byte 2548 da imagem, LBA×4 + 5 (setores de 512). */
    fun hasGrub2BootInfo(data: ByteArray, lba: Long): Boolean =
        data.size >= 2556 && u64(data, 2548) == lba * 4 + 5

    fun applyGrub2BootInfo(data: ByteArray, lba: Long) = put64(data, 2548, lba * 4 + 5)

    fun u64(b: ByteArray, o: Int) = Susp.u32le(b, o) or (Susp.u32le(b, o + 4) shl 32)
    fun put64(b: ByteArray, o: Int, v: Long) { Susp.put32le(b, o, v and 0xFFFFFFFFL); Susp.put32le(b, o + 4, v ushr 32) }
}
