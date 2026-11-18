package dev.bootforge.partition

import dev.bootforge.blockdev.BlockDevice
import dev.bootforge.blockdev.readBlocks
import dev.bootforge.blockdev.readBytes

/** Sistemas de arquivos que o BootForge consegue reconhecer pela assinatura. */
enum class FsType(val label: String) {
    FAT12("FAT12"), FAT16("FAT16"), FAT32("FAT32"), EXFAT("exFAT"), NTFS("NTFS"),
    ISO9660("ISO9660"), UDF("UDF"), EXT("ext2/3/4"), UNKNOWN("Desconhecido"), EMPTY("Vazio (zerado)")
}

data class FsInfo(val type: FsType, val label: String?)

/**
 * Identifica o sistema de arquivos lendo apenas assinaturas conhecidas
 * (não monta nada, não grava nada).
 */
object FsProbe {

    fun probe(dev: BlockDevice): FsInfo {
        if (dev.blockCount == 0L) return FsInfo(FsType.UNKNOWN, null)
        val s0 = dev.readBlocks(0, 1)
        val oem = String(s0, 3, 8, Charsets.US_ASCII)
        when {
            oem == "EXFAT   " -> return FsInfo(FsType.EXFAT, null)
            oem == "NTFS    " -> return FsInfo(FsType.NTFS, null)
        }
        if (LE.u8(s0, 510) == 0x55 && LE.u8(s0, 511) == 0xAA) {
            val bps = LE.u16(s0, 11)
            if (bps in listOf(512, 1024, 2048, 4096)) {
                // FAT: classificamos pela contagem de clusters (regra oficial da Microsoft)
                val spc = LE.u8(s0, 13)
                val reserved = LE.u16(s0, 14)
                val nFats = LE.u8(s0, 16)
                val rootEntries = LE.u16(s0, 17)
                val total16 = LE.u16(s0, 19)
                val fat16Size = LE.u16(s0, 22)
                val total32 = LE.u32(s0, 32)
                val fat32Size = LE.u32(s0, 36)
                if (spc > 0 && nFats > 0 && reserved > 0) {
                    val fatSize = if (fat16Size != 0) fat16Size.toLong() else fat32Size
                    val total = if (total16 != 0) total16.toLong() else total32
                    val rootSectors = ((rootEntries * 32) + bps - 1) / bps
                    val dataSectors = total - (reserved + nFats * fatSize + rootSectors)
                    val clusters = dataSectors / spc
                    val type = when {
                        clusters < 4085 -> FsType.FAT12
                        clusters < 65525 -> FsType.FAT16
                        else -> FsType.FAT32
                    }
                    val labelOff = if (type == FsType.FAT32) 71 else 43
                    val label = String(s0, labelOff, 11, Charsets.US_ASCII).trim().takeIf { it.isNotEmpty() && it != "NO NAME" }
                    return FsInfo(type, label)
                }
            }
        }
        // ext2/3/4: magic 0xEF53 no offset 1024+56
        if (dev.sizeBytes > 2048) {
            val sb = dev.readBytes(1024, 512)
            if (LE.u16(sb, 56) == 0xEF53) return FsInfo(FsType.EXT, String(sb, 120, 16, Charsets.UTF_8).substringBefore('\u0000').ifBlank { null })
        }
        // ISO9660 / UDF: descritores a partir do byte 32768
        if (dev.sizeBytes > 32768 + 2048 * 4) {
            for (i in 0 until 4) {
                val d = dev.readBytes(32768L + i * 2048, 8)
                val id = String(d, 1, 5, Charsets.US_ASCII)
                if (id == "CD001") return FsInfo(FsType.ISO9660, null)
                if (id == "NSR02" || id == "NSR03" || id == "BEA01") return FsInfo(FsType.UDF, null)
            }
        }
        if (s0.all { it.toInt() == 0 }) return FsInfo(FsType.EMPTY, null)
        return FsInfo(FsType.UNKNOWN, null)
    }
}
