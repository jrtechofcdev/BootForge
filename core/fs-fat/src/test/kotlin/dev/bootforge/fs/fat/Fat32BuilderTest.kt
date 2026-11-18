package dev.bootforge.fs.fat

import dev.bootforge.blockdev.FileBlockDevice
import dev.bootforge.integrity.Hashing
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Fat32BuilderTest {

    private fun tool(n: String) = listOf("/usr/sbin/$n", "/sbin/$n", "/usr/bin/$n").firstOrNull { File(it).exists() }

    private fun run(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(120, TimeUnit.SECONDS)
        assertEquals(0, p.exitValue(), "${cmd.joinToString(" ")}\n$out")
        return out
    }

    @Test
    fun buildsValidFat32WithLongNamesAndContiguousFiles() {
        val rnd = Random(3)
        val contents = linkedMapOf(
            "/bootmgr" to rnd.nextBytes(400_000),
            "/BOOTMGR.EFI" to rnd.nextBytes(1_500_000),
            "/autorun.inf" to "[AutoRun]\r\nicon=setup.exe\r\n".toByteArray(),
            "/efi/boot/bootx64.efi" to rnd.nextBytes(1_000_000),
            "/efi/microsoft/boot/fonts/segoe_slboot.ttf" to rnd.nextBytes(100_000),
            "/sources/install.swm" to rnd.nextBytes(3_000_000),
            "/sources/install2.swm" to rnd.nextBytes(2_000_000),
            "/sources/Nome longo com espaços e ç.txt" to "olá".toByteArray(),
            "/sources/arquivo-com-nome-muito-muito-comprido-para-varias-entradas-lfn.dat" to rnd.nextBytes(5000),
            "/sources/vazio.txt" to ByteArray(0),
            "/sources/Setup.Exe" to rnd.nextBytes(70_000),
        )
        repeat(300) { contents["/sources/sxs/pacote-$it.cab"] = rnd.nextBytes(1000 + it) } // pasta grande (vários clusters)
        val root = Fat32Builder.Dir("/")
        contents.forEach { (path, data) ->
            val parts = path.trim('/').split('/')
            var d = root
            parts.dropLast(1).forEach { d = d.dir(it) }
            d.file(parts.last(), data.size.toLong()) { data.inputStream() }
        }
        root.dir("pasta vazia")

        val img = File.createTempFile("bf-fat32", ".img")
        try {
            val result = FileBlockDevice.create(img, 300L shl 20).use { dev ->
                Fat32Builder.build(dev, root, Fat32Builder.Options(label = "CCCOMA_X64FRE", partitionOffsetLba = 0))
            }
            contents.forEach { (p, d) -> assertEquals(Hashing.hash(d), result.sha256[p], p) }
            tool("fsck.fat")?.let { run(it, "-n", "-v", img.path) }

            // leitura com o nosso leitor (stream) e com o mtools (implementação independente)
            FileBlockDevice.open(img, readOnly = true).use { dev ->
                val fat = FatVolume.open(dev)
                assertEquals(FatVolume.Type.FAT32, fat.type)
                assertEquals("CCCOMA_X64F", fat.label)
                contents.forEach { (p, d) -> assertContentEquals(d, fat.openFile(p).use { it.readBytes() }, p) }
                assertTrue(fat.resolve("/pasta vazia")!!.isDirectory)
            }
            tool("mtype")?.let { mtype ->
                val p = ProcessBuilder(mtype, "-i", img.path, "::/EFI/BOOT/BOOTX64.EFI").start()
                val bytes = p.inputStream.readBytes(); p.waitFor()
                assertContentEquals(contents["/efi/boot/bootx64.efi"], bytes)
                val dir = run(tool("mdir")!!, "-i", img.path, "::/sources")
                assertTrue(dir.contains("Nome longo com espa"), dir)
            }
        } finally {
            img.delete()
        }
    }

    @Test
    fun rejectsFilesOver4GiBAndTooSmallVolumes() {
        val root = Fat32Builder.Dir("/")
        root.file("grande.wim", 5L shl 30) { error("não deve ler") }
        assertFailsWith<java.io.IOException> { Fat32Builder.plan(64L shl 21, root, Fat32Builder.Options()) }
        assertFailsWith<java.io.IOException> { Fat32Builder.plan(20L shl 11, Fat32Builder.Dir("/"), Fat32Builder.Options()) }
    }
}
