package dev.bootforge.blockdev

import dev.bootforge.integrity.Hashing
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BlockDeviceTest {

    @Test
    fun byteLevelReadModifyWrite() {
        val dev = MemoryBlockDevice(4096)
        dev.writeBytes(510, byteArrayOf(0x55, 0xAA.toByte(), 1, 2))
        assertContentEquals(byteArrayOf(0x55, 0xAA.toByte(), 1, 2), dev.readBytes(510, 4))
        assertEquals(0, dev.data[509].toInt())
    }

    @Test
    fun subRangeMapsToParent() {
        val dev = MemoryBlockDevice(8 * 512)
        val part = dev.slice(2, 4)
        part.write(0, ByteArray(512) { 7 })
        assertEquals(7, dev.data[2 * 512].toInt())
        assertFailsWith<IOException> { part.read(4, ByteArray(512)) }
    }

    @Test
    fun streamWriteAndVerifyOnFile() {
        val file = File.createTempFile("bootforge", ".img").apply { deleteOnExit() }
        val payload = Random(42).nextBytes(3 * BlockTransfer.CHUNK + 300) // não alinhado de propósito
        FileBlockDevice.create(file, 8L shl 20).use { dev ->
            val result = BlockTransfer.writeStream(dev, 0, ByteArrayInputStream(payload), payload.size.toLong(), "teste")
            assertEquals(Hashing.hash(payload), result.sha256)
            assertEquals(result.sha256, BlockTransfer.hashRegion(dev, 0, payload.size.toLong(), "verificar"))
        }
    }

    @Test
    fun truncatedImageIsRejected() {
        val dev = MemoryBlockDevice(1 shl 20)
        assertFailsWith<IOException> {
            BlockTransfer.writeStream(dev, 0, ByteArrayInputStream(ByteArray(1000)), 2000, "teste")
        }
    }
}
