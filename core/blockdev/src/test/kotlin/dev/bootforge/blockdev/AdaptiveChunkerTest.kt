package dev.bootforge.blockdev

import java.io.IOException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Reproduz o problema do primeiro teste real (Galaxy A16 + pendrive 3207:0300): o pendrive
 * recusa comandos acima de um tamanho e "trava" até ser reiniciado.
 */
class AdaptiveChunkerTest {

    /** Pendrive simulado: recusa comandos > [maxBytes] e fica travado até [reset]. */
    private class PickyDrive(size: Int, val maxBytes: Int) {
        val data = ByteArray(size)
        var stuck = false
        var resets = 0
        val commandSizes = mutableListOf<Int>()
        fun write(lba: Long, src: ByteArray, off: Int, n: Int) {
            if (stuck) throw IOException("MAX_RECOVERY_ATTEMPTS Exceeded")
            commandSizes += n
            if (n > maxBytes) { stuck = true; throw IOException("Could not read from device, result == -1") }
            src.copyInto(data, (lba * 512).toInt(), off, off + n)
        }
        fun reset() { resets++; stuck = false }
    }

    @Test
    fun defaultIs64KAndWorksOnPickyDrive() {
        val drive = PickyDrive(4 shl 20, maxBytes = 64 * 1024)
        val src = Random(1).nextBytes(3 shl 20)
        val c = AdaptiveChunker(AdaptiveChunker.DEFAULT_BYTES, 512)
        c.run(0, src.size, { at, off, n -> drive.write(at, src, off, n) }, drive::reset)
        assertContentEquals(src, drive.data.copyOf(src.size))
        assertEquals(0, drive.resets, "64 KB já funciona: nenhuma recuperação necessária")
        assertTrue(drive.commandSizes.all { it <= 64 * 1024 })
    }

    @Test
    fun shrinksAutomaticallyAndRecoversWithoutReplug() {
        val drive = PickyDrive(4 shl 20, maxBytes = 16 * 1024) // pendrive ainda mais limitado
        val src = Random(2).nextBytes(1 shl 20)
        val shrinks = mutableListOf<Int>()
        val c = AdaptiveChunker(1 shl 20, 512) // começa grande, como a versão 0.6.0
        c.run(0, src.size, { at, off, n -> drive.write(at, src, off, n) }, drive::reset, { b, _ -> shrinks += b })
        assertContentEquals(src, drive.data.copyOf(src.size), "dados gravados corretamente depois de se adaptar")
        assertEquals(16 * 1024, c.chunkBytes)
        assertEquals(listOf(512 * 1024, 256 * 1024, 128 * 1024, 64 * 1024, 32 * 1024, 16 * 1024), shrinks)
        assertEquals(shrinks.size, drive.resets)
    }

    @Test
    fun givesUpWithClearMessageWhenDriveIsDead() {
        val c = AdaptiveChunker(64 * 1024, 512)
        var recoveries = 0
        val e = assertFailsWith<IOException> {
            c.run(0, 1 shl 20, { _, _, _ -> throw IOException("sem resposta") }, { recoveries++ })
        }
        assertTrue(e.message!!.contains("parou de responder"), e.message)
        assertTrue(recoveries in 1..8, "número limitado de tentativas: $recoveries")
    }
}
