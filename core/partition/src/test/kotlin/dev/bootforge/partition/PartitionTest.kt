package dev.bootforge.partition

import dev.bootforge.blockdev.MemoryBlockDevice
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PartitionTest {

    @Test
    fun guidMixedEndianRoundTrip() {
        // Bytes em disco do tipo "Microsoft Basic Data", conforme a especificação UEFI
        val onDisk = byteArrayOf(
            0xA2.toByte(), 0xA0.toByte(), 0xD0.toByte(), 0xEB.toByte(), 0xE5.toByte(), 0xB9.toByte(), 0x33, 0x44,
            0x87.toByte(), 0xC0.toByte(), 0x68, 0xB6.toByte(), 0xB7.toByte(), 0x26, 0x99.toByte(), 0xC7.toByte(),
        )
        assertEquals(GptEntry.TYPE_MICROSOFT_BASIC_DATA, LE.guid(onDisk, 0))
        val out = ByteArray(16)
        LE.putGuid(out, 0, GptEntry.TYPE_MICROSOFT_BASIC_DATA)
        assertTrue(out.contentEquals(onDisk))
    }

    @Test
    fun mbrRoundTrip() {
        val mbr = Mbr(ByteArray(512))
        mbr.setEntry(0, MbrEntry(true, 0x07, 2048, 1000))
        mbr.setBootSignature()
        val back = Mbr(mbr.raw).entry(0)
        assertEquals(2048, back.startLba)
        assertEquals(1000, back.sectorCount)
        assertTrue(back.bootable)
    }

    @Test
    fun gptWriteThenRead() {
        val sectors = 64L * 2048 // 64 MiB
        val dev = MemoryBlockDevice((sectors * 512).toInt())
        val entries = listOf(
            GptEntry(GptEntry.TYPE_MICROSOFT_BASIC_DATA, UUID.randomUUID(), 2048, 4095, 0, "Ventoy"),
            GptEntry(GptEntry.TYPE_EFI_SYSTEM, UUID.randomUUID(), 4096, 8191, Long.MIN_VALUE, "VTOYEFI"),
        )
        val table = Gpt.encodeEntries(entries)
        val header = GptHeader(
            currentLba = 1, backupLba = sectors - 1, firstUsableLba = 34, lastUsableLba = sectors - 34,
            diskGuid = UUID.randomUUID(), entriesLba = 2, entriesCrc = crc32(table),
        )
        val pmbr = Mbr(ByteArray(512)).apply {
            setEntry(0, MbrEntry(false, 0xEE, 1, sectors - 1)); setBootSignature()
        }
        dev.write(0, pmbr.raw)
        dev.write(1, header.encode())
        dev.write(2, table)
        dev.write(sectors - 33, table)
        dev.write(sectors - 1, header.toBackup().encode())

        val layout = DiskLayoutReader.read(dev)
        assertEquals(PartitionScheme.GPT, layout.scheme)
        assertEquals(true, layout.gptEntriesCrcValid)
        assertEquals(true, layout.backupGptValid)
        assertEquals(2, layout.partitions.size)
        assertEquals("VTOYEFI", layout.partitions[1].name)
        assertEquals(Long.MIN_VALUE, layout.partitions[1].gptAttributes)
        assertNotNull(layout.gptHeader)
        assertTrue(layout.warnings.isEmpty(), layout.warnings.toString())
    }

    @Test
    fun ventoyChsMatchesReference() {
        // 8 GiB: cabeças sobem até 255 (o laço "transborda"), setor 2048 → C0 H32 S33
        val chs = Mbr.ventoyChs(8L shl 30, 2048)
        assertEquals(Chs(head = 32, sector = 33, cylinder = 0), chs)
    }
}
