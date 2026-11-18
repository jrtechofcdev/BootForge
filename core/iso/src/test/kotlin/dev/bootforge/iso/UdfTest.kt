package dev.bootforge.iso

import dev.bootforge.blockdev.RandomReader
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UdfTest {

    private fun tool(n: String) = listOf("/usr/bin/$n", "/usr/sbin/$n").firstOrNull { File(it).exists() }

    /** ISO "ponte" ISO9660+UDF, como as do Windows, gerada pelo genisoimage. */
    @Test
    fun readsUdfBridgeIsoLikeWindows() {
        val geniso = tool("genisoimage") ?: return
        val src = File.createTempFile("bf-udfsrc", "").apply { delete(); mkdirs() }
        val iso = File.createTempFile("bf-udf", ".iso")
        try {
            val rnd = Random(11)
            val files = mapOf(
                "sources/install.wim" to rnd.nextBytes(900_000),
                "sources/boot.wim" to rnd.nextBytes(300_001),
                "efi/boot/bootx64.efi" to rnd.nextBytes(150_000),
                "efi/microsoft/boot/fonts/segoe_slboot.ttf" to rnd.nextBytes(77_777),
                "bootmgr" to rnd.nextBytes(400_000),
                "Nome com espaço e acentuação.txt" to "olá".toByteArray(),
                "vazio.txt" to ByteArray(0),
            )
            files.forEach { (p, d) -> File(src, p).apply { parentFile.mkdirs(); writeBytes(d) } }
            val p = ProcessBuilder(geniso, "-quiet", "-input-charset", "utf-8", "-udf", "-iso-level", "3", "-J", "-V", "CCCOMA_X64FRE_PT-BR_DV9", "-o", iso.path, src.path)
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText(); p.waitFor(60, TimeUnit.SECONDS)
            assertEquals(0, p.exitValue(), out)

            RandomReader.of(iso).use { r ->
                val udf = UdfFileSystem.open(r)
                assertEquals("UDF", udf.formatName)
                val seen = mutableMapOf<String, ByteArray>()
                udf.walk { path, e -> if (!e.isDirectory) seen[path.removePrefix("/")] = udf.open(e).use { it.readBytes() } }
                assertEquals(files.keys, seen.keys)
                files.forEach { (k, v) -> assertContentEquals(v, seen[k], k) }
                assertNotNull(udf.lookup("/EFI/BOOT/BOOTX64.EFI")) // sem diferenciar maiúsculas

                // ImageTree.open prefere UDF; o ISO9660/Joliet também deve ler os mesmos dados
                assertEquals("UDF", ImageTree.open(r)!!.formatName)
                val iso9660 = IsoFileSystem.open(r)!!
                assertContentEquals(files["sources/install.wim"], iso9660.open(iso9660.lookup("/sources/install.wim")!!).use { it.readBytes() })

                // identificação como Windows (rótulo + estrutura)
                val info = ImageInspector.inspect(iso.name, r)
                assertEquals(ImageFormat.ISO_UDF_BRIDGE, info.format)
                assertEquals(ImageCategory.WINDOWS, info.os?.category)
            }
            assertTrue(iso.length() > 0)
        } finally {
            src.deleteRecursively(); iso.delete()
        }
    }
}
