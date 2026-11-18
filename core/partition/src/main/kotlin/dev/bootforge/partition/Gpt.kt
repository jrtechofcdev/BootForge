package dev.bootforge.partition

import java.util.UUID
import java.util.zip.CRC32

/**
 * GUID Partition Table (UEFI).
 *
 * Layout em disco (setor de 512 bytes):
 *  - LBA 0          MBR protetor (tipo 0xEE)
 *  - LBA 1          cabeçalho GPT primário ("EFI PART")
 *  - LBA 2..33      128 entradas de 128 bytes
 *  - fim-33..fim-2  cópia das entradas
 *  - fim-1          cabeçalho GPT de backup
 */
data class GptHeader(
    val revision: Long = 0x00010000,
    val headerSize: Int = 92,
    val currentLba: Long,
    val backupLba: Long,
    val firstUsableLba: Long,
    val lastUsableLba: Long,
    val diskGuid: UUID,
    val entriesLba: Long,
    val entryCount: Int = 128,
    val entrySize: Int = 128,
    val entriesCrc: Long,
    /** Resultado da verificação do CRC do próprio cabeçalho (preenchido na leitura). */
    val headerCrcValid: Boolean = true,
) {
    /** Serializa em um setor, calculando o CRC32 do cabeçalho. */
    fun encode(sectorSize: Int = 512): ByteArray {
        val b = ByteArray(sectorSize)
        "EFI PART".toByteArray(Charsets.US_ASCII).copyInto(b, 0)
        LE.put32(b, 8, revision)
        LE.put32(b, 12, headerSize.toLong())
        LE.put32(b, 16, 0) // CRC (calculado abaixo)
        LE.put64(b, 24, currentLba)
        LE.put64(b, 32, backupLba)
        LE.put64(b, 40, firstUsableLba)
        LE.put64(b, 48, lastUsableLba)
        LE.putGuid(b, 56, diskGuid)
        LE.put64(b, 72, entriesLba)
        LE.put32(b, 80, entryCount.toLong())
        LE.put32(b, 84, entrySize.toLong())
        LE.put32(b, 88, entriesCrc)
        LE.put32(b, 16, crc32(b, 0, headerSize))
        return b
    }

    /** Gera o cabeçalho de backup correspondente (mesma regra do `VentoyFillBackupGptHead`). */
    fun toBackup(): GptHeader = copy(
        currentLba = backupLba,
        backupLba = currentLba,
        entriesLba = backupLba + 1 - 33,
    )

    companion object {
        fun decode(b: ByteArray, off: Int = 0): GptHeader? {
            if (String(b, off, 8, Charsets.US_ASCII) != "EFI PART") return null
            val size = LE.u32(b, off + 12).toInt()
            if (size < 92 || size > 512) return null
            val stored = LE.u32(b, off + 16)
            val copy = b.copyOfRange(off, off + size)
            LE.put32(copy, 16, 0)
            return GptHeader(
                revision = LE.u32(b, off + 8),
                headerSize = size,
                currentLba = LE.u64(b, off + 24),
                backupLba = LE.u64(b, off + 32),
                firstUsableLba = LE.u64(b, off + 40),
                lastUsableLba = LE.u64(b, off + 48),
                diskGuid = LE.guid(b, off + 56),
                entriesLba = LE.u64(b, off + 72),
                entryCount = LE.u32(b, off + 80).toInt(),
                entrySize = LE.u32(b, off + 84).toInt(),
                entriesCrc = LE.u32(b, off + 88),
                headerCrcValid = crc32(copy, 0, size) == stored,
            )
        }
    }
}

data class GptEntry(
    val typeGuid: UUID,
    val uniqueGuid: UUID,
    val firstLba: Long,
    val lastLba: Long,
    val attributes: Long,
    val name: String,
) {
    val isEmpty: Boolean get() = typeGuid == ZERO
    val sectorCount: Long get() = lastLba - firstLba + 1

    fun encodeInto(b: ByteArray, off: Int) {
        LE.putGuid(b, off, typeGuid)
        LE.putGuid(b, off + 16, uniqueGuid)
        LE.put64(b, off + 32, firstLba)
        LE.put64(b, off + 40, lastLba)
        LE.put64(b, off + 48, attributes)
        val nameBytes = name.take(36).toByteArray(Charsets.UTF_16LE)
        nameBytes.copyInto(b, off + 56)
    }

    companion object {
        val ZERO = UUID(0, 0)

        /** "Microsoft Basic Data" — usado pelo Ventoy nas DUAS partições (ver nota em [Gpt]). */
        val TYPE_MICROSOFT_BASIC_DATA: UUID = UUID.fromString("ebd0a0a2-b9e5-4433-87c0-68b6b72699c7")
        val TYPE_EFI_SYSTEM: UUID = UUID.fromString("c12a7328-f81f-11d2-ba4b-00a0c93ec93b")
        val TYPE_BIOS_BOOT: UUID = UUID.fromString("21686148-6449-6e6f-744e-656564454649")
        val TYPE_LINUX_DATA: UUID = UUID.fromString("0fc63daf-8483-4772-8e79-3d69d8477de4")

        fun decode(b: ByteArray, off: Int): GptEntry {
            val nameRaw = String(b, off + 56, 72, Charsets.UTF_16LE)
            return GptEntry(
                typeGuid = LE.guid(b, off),
                uniqueGuid = LE.guid(b, off + 16),
                firstLba = LE.u64(b, off + 32),
                lastLba = LE.u64(b, off + 40),
                attributes = LE.u64(b, off + 48),
                name = nameRaw.substringBefore('\u0000'),
            )
        }

        fun typeName(type: UUID): String = when (type) {
            TYPE_MICROSOFT_BASIC_DATA -> "Microsoft Basic Data"
            TYPE_EFI_SYSTEM -> "EFI System"
            TYPE_BIOS_BOOT -> "BIOS Boot"
            TYPE_LINUX_DATA -> "Linux"
            else -> type.toString()
        }
    }
}

object Gpt {
    const val ENTRY_COUNT = 128
    const val ENTRY_SIZE = 128
    /** 128 entradas × 128 bytes = 16 KiB = 32 setores de 512 bytes. */
    const val ENTRIES_BYTES = ENTRY_COUNT * ENTRY_SIZE

    fun encodeEntries(entries: List<GptEntry>): ByteArray {
        require(entries.size <= ENTRY_COUNT)
        val b = ByteArray(ENTRIES_BYTES)
        entries.forEachIndexed { i, e -> e.encodeInto(b, i * ENTRY_SIZE) }
        return b
    }

    fun decodeEntries(b: ByteArray, count: Int = ENTRY_COUNT, size: Int = ENTRY_SIZE): List<GptEntry> =
        (0 until count).map { GptEntry.decode(b, it * size) }
}

fun crc32(b: ByteArray, off: Int = 0, len: Int = b.size): Long =
    CRC32().apply { update(b, off, len) }.value
