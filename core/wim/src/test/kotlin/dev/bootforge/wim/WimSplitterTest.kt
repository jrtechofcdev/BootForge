package dev.bootforge.wim

import dev.bootforge.blockdev.RandomReader
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Valida a divisão com o wimlib oficial: o conjunto .swm gerado pelo BootForge precisa passar no
 * `wimlib-imagex verify` e restaurar exatamente os arquivos originais com `wimlib-imagex apply`.
 */
class WimSplitterTest {

    private fun tool(n: String) = listOf("/usr/bin/$n", "/usr/sbin/$n").firstOrNull { File(it).exists() }

    private fun run(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(300, TimeUnit.SECONDS)
        assertEquals(0, p.exitValue(), "${cmd.joinToString(" ")}\n$out")
        return out
    }

    @Test
    fun splitIsAcceptedByWimlibAndRestoresIdenticalFiles() {
        val wimlib = tool("wimlib-imagex") ?: return
        val work = File.createTempFile("bf-wim", "").apply { delete(); mkdirs() }
        try {
            // árvore com arquivos compressíveis e não compressíveis, em 2 imagens (edições)
            val src = File(work, "src").apply { mkdirs() }
            val rnd = Random(5)
            repeat(60) { i ->
                val f = File(src, "Windows/System32/arq$i.${if (i % 3 == 0) "txt" else "dll"}")
                f.parentFile.mkdirs()
                f.writeBytes(if (i % 3 == 0) ("linha $i\n").repeat(5000).toByteArray() else rnd.nextBytes(20_000 + i * 3000))
            }
            val wim = File(work, "install.wim")
            run(wimlib, "capture", src.path, wim.path, "Windows Home", "--compress=LZX")
            run(wimlib, "append", src.path, wim.path, "Windows Pro")

            val parts = RandomReader.of(wim).use { r ->
                val ps = WimSplitter.plan(r, maxPartBytes = 1_000_000)
                ps.forEach { p -> File(work, p.fileName).outputStream().use { o -> p.open(r).use { it.copyTo(o) } } }
                ps
            }
            assertTrue(parts.size >= 3, "esperava várias partes, veio ${parts.size}")
            assertEquals(listOf("install.swm", "install2.swm", "install3.swm"), parts.take(3).map { it.fileName })
            parts.forEach { p ->
                assertEquals(p.size, File(work, p.fileName).length())
                assertTrue(p.size <= 1_000_000, "${p.fileName} tem ${p.size} bytes")
            }

            val ref = "--ref=${work.path}/install*.swm"
            run(wimlib, "verify", File(work, "install.swm").path, ref)
            val info = run(wimlib, "info", File(work, "install.swm").path)
            assertTrue(info.contains("Part Number:") && info.contains("1/${parts.size}"), info)
            for (image in 1..2) {
                val out = File(work, "out$image")
                run(wimlib, "apply", File(work, "install.swm").path, image.toString(), out.path, ref)
                src.walkTopDown().filter { it.isFile }.forEach { f ->
                    assertContentEquals(f.readBytes(), File(out, f.relativeTo(src).path).readBytes(), f.name)
                }
            }
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun smallWimNeedsNoSplitAndStaysValid() {
        val wimlib = tool("wimlib-imagex") ?: return
        val work = File.createTempFile("bf-wim1", "").apply { delete(); mkdirs() }
        try {
            File(work, "src/a.txt").apply { parentFile.mkdirs(); writeText("BootForge") }
            val wim = File(work, "install.wim")
            run(wimlib, "capture", File(work, "src").path, wim.path, "x")
            val parts = RandomReader.of(wim).use { WimSplitter.plan(it) }
            assertEquals(1, parts.size)
            assertEquals(1, RandomReader.of(wim).use { WimSplitter.info(it) }.imageCount)
        } finally {
            work.deleteRecursively()
        }
    }
}
