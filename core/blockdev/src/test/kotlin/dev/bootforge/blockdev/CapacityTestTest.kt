package dev.bootforge.blockdev

import dev.bootforge.integrity.Hashing
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pendrives falsificados simulados: o teste precisa detectar e estimar a capacidade real. */
class CapacityTestTest {

    /** Falso do tipo "volta ao início": anuncia [declaredBlocks], mas o endereço dá a volta no tamanho real. */
    private class WrappingFake(private val real: MemoryBlockDevice, override val blockCount: Long) : BlockDevice {
        override val blockSize = 512
        override val isReadOnly = false
        private fun map(lba: Long) = lba % real.blockCount
        override fun read(lba: Long, buffer: ByteArray, offset: Int, length: Int) {
            for (i in 0 until length / 512) real.read(map(lba + i), buffer, offset + i * 512, 512)
        }
        override fun write(lba: Long, buffer: ByteArray, offset: Int, length: Int) {
            for (i in 0 until length / 512) real.write(map(lba + i), buffer, offset + i * 512, 512)
        }
        override fun flush() = Unit
        override fun close() = Unit
    }

    /** Falso do tipo "joga fora": além do tamanho real, a gravação some e a leitura devolve zeros. */
    private class DiscardingFake(private val real: MemoryBlockDevice, override val blockCount: Long) : BlockDevice {
        override val blockSize = 512
        override val isReadOnly = false
        override fun read(lba: Long, buffer: ByteArray, offset: Int, length: Int) {
            for (i in 0 until length / 512) {
                val l = lba + i
                if (l < real.blockCount) real.read(l, buffer, offset + i * 512, 512) else buffer.fill(0, offset + i * 512, offset + (i + 1) * 512)
            }
        }
        override fun write(lba: Long, buffer: ByteArray, offset: Int, length: Int) {
            for (i in 0 until length / 512) { val l = lba + i; if (l < real.blockCount) real.write(l, buffer, offset + i * 512, 512) }
        }
        override fun flush() = Unit
        override fun close() = Unit
    }

    private fun filled(blocks: Long) = MemoryBlockDevice(ByteArray((blocks * 512).toInt())).also { d ->
        val r = Random(9); val buf = ByteArray(1 shl 20)
        var l = 0L
        while (l < blocks) { r.nextBytes(buf); val n = minOf(2048L, blocks - l).toInt(); d.write(l, buf, 0, n * 512); l += n }
    }

    @Test
    fun genuineDriveIsConfirmedAndDataIsPreserved() {
        val f = File.createTempFile("bf-cap", ".img")
        try {
            val dev = FileBlockDevice.create(f, 64L shl 20)
            val rnd = Random(1); val chunk = ByteArray(1 shl 20)
            for (i in 0 until 64) { rnd.nextBytes(chunk); dev.write(i * 2048L, chunk) }
            dev.flush()
            val before = Hashing.hash(f)
            val r = CapacityTest.run(dev)
            assertTrue(r.genuine); assertTrue(r.restored)
            assertEquals(64L shl 20, r.realBytesEstimate)
            dev.close()
            assertEquals(before, Hashing.hash(f), "o teste não pode alterar nenhum byte do pendrive")
        } finally { f.delete() }
    }

    @Test
    fun wrappingFakeIsDetectedWithRealSizeEstimate() {
        val real = filled(16L shl 11) // 16 MiB reais
        val realBefore = ByteArray((16 shl 20)).also { real.read(0, it) }
        val fake = WrappingFake(real, 128L shl 11) // anuncia 128 MiB
        val r = CapacityTest.run(fake)
        assertFalse(r.genuine)
        assertTrue(r.wraps, "deveria perceber que o pendrive dá a volta")
        assertEquals(16L shl 20, r.realBytesEstimate, "capacidade real exata")
        // a parte real volta ao original (restauração do fim para o começo)
        val after = ByteArray(16 shl 20).also { real.read(0, it) }
        assertTrue(after.contentEquals(realBefore), "dados da parte real preservados")
    }

    @Test
    fun discardingFakeIsDetected() {
        val real = filled(8L shl 11)
        val r = CapacityTest.run(DiscardingFake(real, 64L shl 11))
        assertFalse(r.genuine)
        assertTrue(r.realBytesEstimate <= 8L shl 20, "estimativa ${r.realBytesEstimate}")
        assertTrue(r.badPoints > r.points / 2)
    }
}
