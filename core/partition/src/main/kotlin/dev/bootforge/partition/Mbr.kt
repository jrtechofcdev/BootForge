package dev.bootforge.partition

/**
 * Master Boot Record (setor 0 do disco).
 *
 * Estrutura (512 bytes):
 *  - 0x000..0x1B7  código de boot (440 bytes)
 *  - 0x1B8..0x1BB  assinatura do disco (Disk Signature)
 *  - 0x1BE..0x1FD  4 entradas de partição de 16 bytes
 *  - 0x1FE..0x1FF  assinatura 0x55 0xAA
 */
data class MbrEntry(
    val bootable: Boolean,
    /** Tipo da partição: 0x07 NTFS/exFAT, 0x0C FAT32 LBA, 0xEF ESP, 0xEE GPT protetora... */
    val type: Int,
    val startLba: Long,
    val sectorCount: Long,
    /** Campos CHS (legado) — preservados para escrita fiel. */
    val startChs: Chs = Chs(0, 0, 0),
    val endChs: Chs = Chs(0, 0, 0),
) {
    val isEmpty: Boolean get() = type == 0 || sectorCount == 0L
    val endLba: Long get() = startLba + sectorCount - 1
}

data class Chs(val head: Int, val sector: Int, val cylinder: Int)

class Mbr(val raw: ByteArray) {
    init {
        require(raw.size >= 512) { "O MBR precisa de 512 bytes" }
    }

    val hasBootSignature: Boolean get() = LE.u8(raw, 510) == 0x55 && LE.u8(raw, 511) == 0xAA
    val diskSignature: Long get() = LE.u32(raw, 0x1B8)
    val entries: List<MbrEntry> get() = (0 until 4).map { entry(it) }

    fun entry(i: Int): MbrEntry {
        val o = 0x1BE + i * 16
        return MbrEntry(
            bootable = LE.u8(raw, o) == 0x80,
            type = LE.u8(raw, o + 4),
            startLba = LE.u32(raw, o + 8),
            sectorCount = LE.u32(raw, o + 12),
            startChs = decodeChs(raw, o + 1),
            endChs = decodeChs(raw, o + 5),
        )
    }

    /**
     * Grava uma entrada.
     * @param ventoyChsBitfield quando true, codifica o CHS exatamente como o Ventoy2Disk faz
     * (struct com bitfield `UINT16 Sector:6, Cylinder:10`), que difere do padrão IBM.
     * O CHS é ignorado por firmwares modernos (que usam LBA); mantemos só por fidelidade.
     */
    fun setEntry(i: Int, e: MbrEntry, ventoyChsBitfield: Boolean = false) {
        val o = 0x1BE + i * 16
        raw[o] = if (e.bootable) 0x80.toByte() else 0
        val enc: (ByteArray, Int, Chs) -> Unit = if (ventoyChsBitfield) ::encodeChsBitfield else ::encodeChs
        enc(raw, o + 1, e.startChs)
        raw[o + 4] = e.type.toByte()
        enc(raw, o + 5, e.endChs)
        LE.put32(raw, o + 8, e.startLba)
        LE.put32(raw, o + 12, e.sectorCount)
    }

    fun setBootSignature() {
        raw[510] = 0x55; raw[511] = 0xAA.toByte()
    }

    /** true quando a primeira entrada é do tipo 0xEE (MBR protetor de um disco GPT). */
    val isProtectiveForGpt: Boolean get() = entries.any { it.type == 0xEE }

    companion object {
        const val TYPE_EMPTY = 0x00
        const val TYPE_FAT32_LBA = 0x0C
        const val TYPE_NTFS_EXFAT = 0x07
        const val TYPE_EFI_SYSTEM = 0xEF
        const val TYPE_GPT_PROTECTIVE = 0xEE

        private fun decodeChs(b: ByteArray, o: Int): Chs {
            val head = LE.u8(b, o)
            val sec = LE.u8(b, o + 1)
            val cyl = LE.u8(b, o + 2) or ((sec and 0xC0) shl 2)
            return Chs(head, sec and 0x3F, cyl)
        }

        private fun encodeChs(b: ByteArray, o: Int, chs: Chs) {
            b[o] = chs.head.toByte()
            b[o + 1] = ((chs.sector and 0x3F) or ((chs.cylinder ushr 2) and 0xC0)).toByte()
            b[o + 2] = (chs.cylinder and 0xFF).toByte()
        }

        private fun encodeChsBitfield(b: ByteArray, o: Int, chs: Chs) {
            b[o] = chs.head.toByte()
            val v = (chs.sector and 0x3F) or ((chs.cylinder and 0x3FF) shl 6)
            b[o + 1] = (v and 0xFF).toByte()
            b[o + 2] = ((v ushr 8) and 0xFF).toByte()
        }

        /**
         * Calcula CHS exatamente como o Ventoy2Disk (função `VentoyFillMBRLocation`, Utility.c):
         * 63 setores por trilha; cabeças começam em 8 e dobram até o cilindro caber em 1024
         * (o contador de 8 bits "transborda" para 0 → usa 255). Valores de cilindro são truncados
         * na codificação (10 bits), como no original.
         */
        fun ventoyChs(diskSizeBytes: Long, lba: Long): Chs {
            val nSector = 63L
            var nHead = 8
            while (nHead != 0 && (diskSizeBytes / 512 / nSector / nHead) > 1024) {
                nHead = (nHead * 2) and 0xFF
            }
            if (nHead == 0) nHead = 255
            val cylinder = lba / nSector / nHead
            val head = (lba / nSector % nHead).toInt()
            val sector = (lba % nSector + 1).toInt()
            return Chs(head and 0xFF, sector, (cylinder and 0xFFFFFFFFL).toInt())
        }
    }
}
