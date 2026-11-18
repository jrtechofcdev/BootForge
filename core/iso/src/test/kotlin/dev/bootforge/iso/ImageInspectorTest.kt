package dev.bootforge.iso

import dev.bootforge.blockdev.RandomReader
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ImageInspectorTest {

    private fun tool(n: String) = listOf("/usr/bin/$n", "/usr/sbin/$n").firstOrNull { File(it).exists() }

    private fun run(vararg cmd: String) {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(120, TimeUnit.SECONDS)
        assertEquals(0, p.exitValue(), out)
    }

    /** ISO real e inicializável (BIOS + UEFI, híbrida) gerada pelo grub-mkrescue, com /.disk/info de Ubuntu. */
    @Test
    fun identifiesRealBootableIso() {
        val mkrescue = tool("grub-mkrescue") ?: return
        val dir = File.createTempFile("bf-isosrc", "").apply { delete(); mkdirs() }
        File(dir, "boot/grub").mkdirs(); File(dir, "boot/grub/grub.cfg").writeText("menuentry x { echo ok }\n")
        File(dir, ".disk").mkdirs(); File(dir, ".disk/info").writeText("Ubuntu 24.04.1 LTS \"Noble Numbat\" - Release amd64 (20240827)\n")
        File(dir, "casper").mkdirs(); File(dir, "casper/filesystem.size").writeText("1")
        val iso = File.createTempFile("bf-test", ".iso")
        try {
            run(mkrescue, "-o", iso.path, dir.path, "--", "-volid", "Ubuntu 24.04.1 LTS amd64")
            val info = RandomReader.of(iso).use { ImageInspector.inspect(iso.name, it) }
            assertTrue(info.elTorito && info.bootBios && info.bootUefi, info.toString())
            assertTrue(info.hybridMbr)
            assertEquals("Ubuntu", info.os?.name?.substringBefore(' ')?.takeIf { it == "Ubuntu" } ?: info.os?.name?.take(6))
            assertEquals(ImageCategory.LINUX, info.os?.category)
            assertNotNull(info.diskInfo)
            assertTrue(info.problems.isEmpty(), info.problems.toString())
            // arquivo truncado: o tamanho declarado no ISO9660 fica maior que o arquivo
            val cut = File.createTempFile("bf-cut", ".iso")
            cut.writeBytes(iso.readBytes().copyOf((iso.length() * 0.6).toInt()))
            val t = RandomReader.of(cut).use { ImageInspector.inspect(cut.name, it) }
            assertTrue(t.isTruncated && t.problems.any { it.contains("TRUNCADO") })
            cut.delete()
        } finally {
            iso.delete(); dir.deleteRecursively()
        }
    }

    @Test
    fun windowsVolumeLabelsAndMarkers() {
        assertEquals(ImageCategory.WINDOWS, ImageInspector.guessOs("CCCOMA_X64FRE_PT-BR_DV9", null, emptySet(), true)?.category)
        assertEquals(ImageCategory.WINDOWS, ImageInspector.guessOs("XYZ", null, setOf("sources/install.wim"), false)?.category)
        assertEquals("Linux Mint", ImageInspector.guessOs("Linux Mint 22 Cinnamon 64-bit", null, emptySet(), false)?.name)
        assertEquals(ImageCategory.RESCUE, ImageInspector.guessOs("GParted-live", null, emptySet(), false)?.category)
    }

    @Test
    fun nonIsoIsReportedHonestly() {
        val r = RandomReader.of(ByteArray(100_000)).use { ImageInspector.inspect("x.iso", it) }
        assertEquals(ImageFormat.UNKNOWN, r.format)
        assertTrue(r.problems.isNotEmpty())
    }
}
