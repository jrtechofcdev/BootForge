package dev.bootforge.partition

import dev.bootforge.blockdev.BlockDevice
import dev.bootforge.blockdev.readBlocks
import dev.bootforge.blockdev.slice

enum class PartitionScheme(val label: String) {
    NONE("Sem tabela de partição"),
    MBR("MBR (DOS)"),
    GPT("GPT"),
    /** Imagem gravada direto no disco (ex.: ISO híbrida), sem partições reconhecidas. */
    SUPERFLOPPY("Sistema de arquivos sem partição"),
}

data class PartitionInfo(
    /** Número da partição começando em 1 (como no Linux: sda1, sda2...). */
    val number: Int,
    val startLba: Long,
    val sectorCount: Long,
    /** Descrição do tipo (ex.: "0x07", "Microsoft Basic Data"). */
    val typeDescription: String,
    val mbrType: Int? = null,
    val gptType: java.util.UUID? = null,
    val gptAttributes: Long? = null,
    val name: String? = null,
    val bootable: Boolean = false,
    val fs: FsInfo? = null,
) {
    val endLba: Long get() = startLba + sectorCount - 1
}

/** Resultado da leitura da tabela de partições de um dispositivo. */
data class DiskLayout(
    val scheme: PartitionScheme,
    val sectorSize: Int,
    val totalSectors: Long,
    val partitions: List<PartitionInfo>,
    val mbr: Mbr?,
    val gptHeader: GptHeader?,
    val gptEntriesCrcValid: Boolean? = null,
    val backupGptValid: Boolean? = null,
    /** Problemas encontrados (ex.: CRC inválido, partição fora do disco). */
    val warnings: List<String> = emptyList(),
    /** Sistema de arquivos encontrado diretamente no setor 0 (sem partições). */
    val wholeDiskFs: FsInfo? = null,
)

/** Lê MBR/GPT de um dispositivo, sem gravar nada. */
object DiskLayoutReader {

    fun read(dev: BlockDevice, probeFilesystems: Boolean = true): DiskLayout {
        val warnings = mutableListOf<String>()
        val s0 = dev.readBlocks(0, 1)
        val mbr = Mbr(s0.copyOf(512))
        val wholeFs = runCatching { FsProbe.probe(dev) }.getOrNull()

        if (!mbr.hasBootSignature) {
            val scheme = if (wholeFs != null && wholeFs.type !in setOf(FsType.UNKNOWN, FsType.EMPTY)) PartitionScheme.SUPERFLOPPY else PartitionScheme.NONE
            return DiskLayout(scheme, dev.blockSize, dev.blockCount, emptyList(), null, null, wholeDiskFs = wholeFs)
        }

        // Um setor 0 com assinatura 55AA pode ser um setor de boot FAT/exFAT/NTFS sem tabela.
        if (wholeFs != null && wholeFs.type in setOf(FsType.FAT12, FsType.FAT16, FsType.FAT32, FsType.EXFAT, FsType.NTFS)) {
            return DiskLayout(PartitionScheme.SUPERFLOPPY, dev.blockSize, dev.blockCount, emptyList(), mbr, null, wholeDiskFs = wholeFs)
        }

        if (mbr.isProtectiveForGpt) {
            val hdrSector = dev.readBlocks(1, 1)
            val header = GptHeader.decode(hdrSector)
            if (header != null) {
                if (!header.headerCrcValid) warnings += "CRC do cabeçalho GPT primário inválido"
                val entriesBytes = header.entryCount * header.entrySize
                val entriesSectors = (entriesBytes + dev.blockSize - 1) / dev.blockSize
                val raw = dev.readBlocks(header.entriesLba, entriesSectors)
                val entriesCrcOk = crc32(raw, 0, entriesBytes) == header.entriesCrc
                if (!entriesCrcOk) warnings += "CRC da tabela de entradas GPT inválido"
                val backupOk = runCatching {
                    val b = GptHeader.decode(dev.readBlocks(header.backupLba, 1))
                    b != null && b.headerCrcValid && b.entriesCrc == header.entriesCrc
                }.getOrDefault(false)
                if (!backupOk) warnings += "Cabeçalho GPT de backup ausente ou divergente"
                val parts = Gpt.decodeEntries(raw, header.entryCount, header.entrySize)
                    .mapIndexedNotNull { i, e ->
                        if (e.isEmpty) null else PartitionInfo(
                            number = i + 1,
                            startLba = e.firstLba,
                            sectorCount = e.sectorCount,
                            typeDescription = GptEntry.typeName(e.typeGuid),
                            gptType = e.typeGuid,
                            gptAttributes = e.attributes,
                            name = e.name,
                        )
                    }
                return DiskLayout(
                    PartitionScheme.GPT, dev.blockSize, dev.blockCount,
                    probe(dev, parts, probeFilesystems, warnings), mbr, header, entriesCrcOk, backupOk, warnings,
                )
            }
            warnings += "MBR protetor encontrado, mas o cabeçalho GPT é inválido"
        }

        val parts = mbr.entries.mapIndexedNotNull { i, e ->
            if (e.isEmpty) null else PartitionInfo(
                number = i + 1,
                startLba = e.startLba,
                sectorCount = e.sectorCount,
                typeDescription = "0x%02X %s".format(e.type, mbrTypeName(e.type)),
                mbrType = e.type,
                bootable = e.bootable,
            )
        }
        return DiskLayout(PartitionScheme.MBR, dev.blockSize, dev.blockCount, probe(dev, parts, probeFilesystems, warnings), mbr, null, warnings = warnings)
    }

    private fun probe(dev: BlockDevice, parts: List<PartitionInfo>, enabled: Boolean, warnings: MutableList<String>): List<PartitionInfo> =
        parts.map { p ->
            if (p.startLba + p.sectorCount > dev.blockCount) {
                warnings += "Partição ${p.number} ultrapassa o fim do disco"
                return@map p
            }
            if (!enabled) p else p.copy(fs = runCatching { FsProbe.probe(dev.slice(p.startLba, p.sectorCount)) }.getOrNull())
        }

    fun mbrTypeName(type: Int): String = when (type) {
        0x01 -> "FAT12"
        0x04, 0x06, 0x0E -> "FAT16"
        0x05, 0x0F -> "Estendida"
        0x07 -> "NTFS/exFAT"
        0x0B, 0x0C -> "FAT32"
        0x17 -> "NTFS oculta"
        0x82 -> "Linux swap"
        0x83 -> "Linux"
        0xEE -> "GPT protetora"
        0xEF -> "EFI System"
        else -> ""
    }
}
