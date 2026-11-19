package dev.bootforge.partition

import dev.bootforge.blockdev.BlockDevice
import dev.bootforge.blockdev.zeroBlocks
import java.io.IOException
import java.util.UUID

/**
 * Cria a tabela de partições de um disco com UMA partição (pendrive formatado, mídia gravada por
 * extração etc.). Usado pelo gravador de ISO e pelas ferramentas de formatação.
 *
 * Ordem segura recomendada para quem chama: [wipeTables] → criar o sistema de arquivos dentro da
 * partição → [writeSingle] por último (se algo falhar no meio, o disco não aparenta estar pronto).
 */
object PartitionWriter {

    /** Primeira partição em 1 MiB (alinhamento padrão das ferramentas modernas). */
    const val START = 2048L

    /** Início e tamanho da partição: de 1 MiB até o fim, alinhado em 1 MiB. */
    fun range(totalSectors: Long, gpt: Boolean): Pair<Long, Long> {
        val end = if (gpt) totalSectors - 33 else minOf(totalSectors, 0xFFFFFFFFL) // GPT: espaço para a cópia no fim
        val alignedEnd = end / 2048 * 2048
        if (alignedEnd - START < 2048) throw IOException("Dispositivo pequeno demais")
        return START to (alignedEnd - START)
    }

    /** Apaga o primeiro e o último MiB (tabelas antigas, inclusive a cópia do GPT no fim). */
    fun wipeTables(dev: BlockDevice) {
        val total = dev.blockCount
        dev.zeroBlocks(0, minOf(START, total))
        dev.zeroBlocks(maxOf(0, total - 2048), minOf(2048L, total))
        dev.flush()
    }

    /**
     * Grava a tabela com uma partição.
     * @param mbrType tipo no MBR (0x0C FAT32, 0x07 exFAT/NTFS); no GPT usa "Microsoft Basic Data",
     *   que o Windows, o Android e o firmware UEFI reconhecem.
     * @param bootCode até 440 bytes de código de boot para o MBR (opcional).
     */
    fun writeSingle(
        dev: BlockDevice, gpt: Boolean, start: Long, count: Long, mbrType: Int,
        bootable: Boolean = false, name: String = "BootForge", bootCode: ByteArray? = null,
    ) {
        val raw = ByteArray(512)
        bootCode?.copyInto(raw, 0, 0, minOf(440, bootCode.size))
        val mbr = Mbr(raw)
        if (!gpt) {
            mbr.setEntry(0, MbrEntry(bootable, mbrType, start, count, chs(start), chs(start + count - 1)))
            val sig = UUID.randomUUID().leastSignificantBits
            for (i in 0 until 4) raw[0x1B8 + i] = (sig ushr (8 * i)).toByte()
            mbr.setBootSignature()
            dev.write(0, raw)
            return
        }
        val total = dev.blockCount
        mbr.setEntry(0, MbrEntry(false, Mbr.TYPE_GPT_PROTECTIVE, 1, minOf(total - 1, 0xFFFFFFFFL), Chs(0, 2, 0), Chs(254, 63, 1023)))
        mbr.setBootSignature()
        val entries = Gpt.encodeEntries(listOf(GptEntry(GptEntry.TYPE_MICROSOFT_BASIC_DATA, UUID.randomUUID(), start, start + count - 1, 0, name)))
        val header = GptHeader(
            currentLba = 1, backupLba = total - 1, firstUsableLba = 34, lastUsableLba = total - 34,
            diskGuid = UUID.randomUUID(), entriesLba = 2, entriesCrc = crc32(entries),
        )
        dev.write(total - 33, entries)
        dev.write(total - 1, header.toBackup().encode())
        val primary = ByteArray(34 * 512)
        raw.copyInto(primary, 0)
        header.encode().copyInto(primary, 512)
        entries.copyInto(primary, 1024)
        dev.write(0, primary)
    }

    /** CHS no padrão IBM (255 cabeças × 63 setores); acima de 8 GB usa o valor máximo. */
    fun chs(lba: Long): Chs {
        val cyl = lba / (255 * 63)
        if (cyl > 1023) return Chs(254, 63, 1023)
        return Chs(((lba / 63) % 255).toInt(), (lba % 63 + 1).toInt(), cyl.toInt())
    }
}
