package dev.bootforge.isoedit

import dev.bootforge.blockdev.RandomReader
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Etapa 7: falhas durante a geração da nova ISO e imagens de entrada corrompidas. */
class StabilityTest {
    private fun tool(n: String) = listOf("/usr/bin/$n", "/usr/sbin/$n").firstOrNull { File(it).exists() }

    @Test
    fun writeFailureIsReportedAndOriginalStaysUntouched() {
        val geniso = tool("genisoimage") ?: return
        val work = File.createTempFile("bf-st-ed", "").apply { delete(); mkdirs() }
        try {
            val t = File(work, "t").apply { mkdirs() }
            File(t, "a.bin").writeBytes(Random(1).nextBytes(3_000_000))
            val iso = File(work, "o.iso")
            ProcessBuilder(geniso, "-quiet", "-R", "-J", "-o", iso.path, t.path).start().apply { waitFor(60, TimeUnit.SECONDS) }
            val before = iso.readBytes()
            RandomReader.of(iso).use { r ->
                val s = EditSession(IsoSource.open(r))
                s.addFile("/", "novo.txt", BytesContent("x".toByteArray(), "x"))
                val plan = IsoBuilder.plan(s, RebuildMode.PRESERVE)
                // destino que "enche" (cartão cheio / pendrive removido) depois de 1 MB
                val full = object : OutputStream() {
                    var n = 0L
                    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                    override fun write(b: ByteArray, off: Int, len: Int) { n += len; if (n > 1_000_000) throw IOException("No space left on device") }
                }
                assertFailsWith<IOException> { plan.write(full) }
            }
            assertContentEquals(before, iso.readBytes(), "a ISO original nunca é alterada")
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun truncatedOrInvalidImagesAreRejectedWithClearErrors() {
        val geniso = tool("genisoimage") ?: return
        val work = File.createTempFile("bf-st-ed2", "").apply { delete(); mkdirs() }
        try {
            val t = File(work, "t").apply { mkdirs() }
            File(t, "grande.bin").writeBytes(Random(2).nextBytes(4_000_000))
            val iso = File(work, "o.iso")
            ProcessBuilder(geniso, "-quiet", "-R", "-J", "-o", iso.path, t.path).start().apply { waitFor(60, TimeUnit.SECONDS) }
            // download incompleto: metade do arquivo
            val cut = File(work, "cortada.iso").apply { writeBytes(iso.readBytes().copyOf((iso.length() / 2).toInt())) }
            RandomReader.of(cut).use { r ->
                val s = EditSession(IsoSource.open(r))
                val plan = IsoBuilder.plan(s, RebuildMode.FULL)
                val e = assertFailsWith<IOException> { File(work, "x.iso").outputStream().use { plan.write(it) } }
                assertTrue(e.message!!.isNotBlank())
            }
            // arquivo que não é ISO
            val junk = File(work, "lixo.iso").apply { writeBytes(Random(3).nextBytes(200_000)) }
            val e2 = assertFailsWith<IOException> { RandomReader.of(junk).use { IsoSource.open(it) } }
            assertEquals(true, e2.message?.contains("ISO9660"), e2.message)
        } finally {
            work.deleteRecursively()
        }
    }
}
