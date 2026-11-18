package dev.bootforge.isowriter

import dev.bootforge.blockdev.FileBlockDevice
import dev.bootforge.blockdev.RandomReader
import dev.bootforge.integrity.Hashing
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Testa o motor de gravação de ponta a ponta em arquivos .img, conferindo o resultado com
 * ferramentas independentes do Linux (fsck.fat, sfdisk, sgdisk, mtools, wimlib).
 * Testes cujas ferramentas não estão instaladas são pulados.
 */
class IsoWriterTest {

    private fun tool(n: String) = listOf("/usr/bin/$n", "/usr/sbin/$n", "/sbin/$n").firstOrNull { File(it).exists() }

    private fun run(vararg cmd: String, ok: Boolean = true): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(300, TimeUnit.SECONDS)
        if (ok) assertEquals(0, p.exitValue(), "${cmd.joinToString(" ")}\n$out")
        return out
    }

    private fun tempDir(prefix: String) = File.createTempFile(prefix, "").apply { delete(); mkdirs() }

    /** Extrai um arquivo de dentro da partição do .img com o mtools (offset de 1 MiB). */
    private fun mcopy(img: File, path: String, out: File) {
        run(tool("mcopy")!!, "-n", "-i", "${img.path}@@1048576", "::$path", out.path)
    }

    @Test
    fun windowsIsoIsWrittenAsFat32WithSplitWim() {
        val geniso = tool("genisoimage") ?: return
        val wimlib = tool("wimlib-imagex") ?: return
        if (tool("mcopy") == null) return
        val work = tempDir("bf-win")
        try {
            // "ISO do Windows" mínima: mesma estrutura (UDF + bootmgr + efi/boot + sources/*.wim)
            val tree = File(work, "tree")
            val rnd = Random(9)
            fun put(p: String, data: ByteArray) = File(tree, p).apply { parentFile.mkdirs(); writeBytes(data) }
            put("bootmgr", rnd.nextBytes(300_000))
            put("bootmgr.efi", rnd.nextBytes(900_000))
            put("efi/boot/bootx64.efi", rnd.nextBytes(1_200_000))
            put("efi/microsoft/boot/bcd", rnd.nextBytes(16_384))
            put("sources/boot.wim", rnd.nextBytes(2_000_000))
            put("sources/Nome Longo do Instalador.txt", "ok".toByteArray())
            val wimSrc = File(work, "wimsrc").apply { mkdirs() }
            repeat(40) { File(wimSrc, "Windows/arq$it.dll").apply { parentFile.mkdirs(); writeBytes(rnd.nextBytes(30_000 + it * 2000)) } }
            run(wimlib, "capture", wimSrc.path, File(tree, "sources/install.wim").path, "Windows 11 Pro")
            val iso = File(work, "win.iso")
            run(geniso, "-quiet", "-udf", "-iso-level", "3", "-J", "-input-charset", "utf-8", "-V", "CCCOMA_X64FRE_PT-BR_DV9", "-o", iso.path, tree.path)

            val analysis = RandomReader.of(iso).use { IsoAnalyzer.analyze("win.iso", it) }
            assertTrue(analysis.isWindows, analysis.toString())
            assertEquals(WriteMethod.WINDOWS_FAT32, analysis.recommended)
            assertFalse(analysis.option(WriteMethod.DD).available)
            assertEquals(listOf("/efi/boot/bootx64.efi"), analysis.efiLoaders.map { it.lowercase() })

            for (scheme in PartitionScheme.entries) {
                val img = File(work, "usb-$scheme.img")
                val report = FileBlockDevice.create(img, 400L shl 20).use { dev ->
                    RandomReader.of(iso).use { src ->
                        IsoWriter.write(dev, src, analysis, WriteRequest(WriteMethod.WINDOWS_FAT32, scheme, wimPartLimit = 400_000))
                    }
                }
                assertTrue(report.verified)
                assertEquals("CCCOMA_X64F", report.label)
                assertTrue(report.wimParts.size >= 3, report.wimParts.toString())
                assertEquals("/sources/install.swm", report.wimParts.first().lowercase())

                when (scheme) {
                    PartitionScheme.MBR -> tool("sfdisk")?.let {
                        val d = run(it, "-d", img.path)
                        assertTrue(d.contains("label: dos") && d.contains("start=        2048") && d.contains("type=c") && d.contains("bootable"), d)
                    }
                    PartitionScheme.GPT -> tool("sgdisk")?.let {
                        val v = run(it, "-v", img.path)
                        assertTrue(v.contains("No problems found"), v)
                    }
                }
                // fsck.fat na partição (extraída do .img)
                tool("fsck.fat")?.let { fsck ->
                    val part = File(work, "part.img")
                    img.inputStream().use { i -> i.skip(1L shl 20); part.outputStream().use { o -> i.copyTo(o) } }
                    run(fsck, "-n", part.path)
                    part.delete()
                }
                // arquivos idênticos aos da ISO + .swm aceitos pelo wimlib
                val out = File(work, "out-$scheme").apply { mkdirs() }
                for (p in listOf("bootmgr", "efi/boot/bootx64.efi", "sources/boot.wim", "sources/Nome Longo do Instalador.txt")) {
                    val f = File(out, p.replace('/', '_'))
                    mcopy(img, "/$p", f)
                    assertContentEquals(File(tree, p).readBytes(), f.readBytes(), p)
                }
                report.wimParts.forEach { mcopy(img, it, File(out, it.substringAfterLast('/'))) }
                val ref = "--ref=${out.path}/install*.swm"
                run(wimlib, "verify", File(out, "install.swm").path, ref)
                val applied = File(out, "applied")
                run(wimlib, "apply", File(out, "install.swm").path, "1", applied.path, ref)
                wimSrc.walkTopDown().filter { it.isFile }.forEach { f ->
                    assertContentEquals(f.readBytes(), File(applied, f.relativeTo(wimSrc).path).readBytes())
                }
            }
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun nonHybridUefiIsoIsExtractedToFat32() {
        val geniso = tool("genisoimage") ?: return
        if (tool("mcopy") == null) return
        val work = tempDir("bf-ext")
        try {
            val tree = File(work, "tree")
            val rnd = Random(4)
            val files = mapOf(
                "EFI/BOOT/BOOTX64.EFI" to rnd.nextBytes(700_000),
                "boot/grub/grub.cfg" to "menuentry teste {}\n".toByteArray(),
                "live/vmlinuz" to rnd.nextBytes(3_000_000),
                "live/filesystem.squashfs" to rnd.nextBytes(9_000_000),
                ".disk/info" to "Distro Teste".toByteArray(),
            )
            files.forEach { (p, d) -> File(tree, p).apply { parentFile.mkdirs(); writeBytes(d) } }
            val iso = File(work, "linux.iso")
            run(geniso, "-quiet", "-R", "-J", "-input-charset", "utf-8", "-V", "DISTRO_TESTE", "-o", iso.path, tree.path)

            val analysis = RandomReader.of(iso).use { IsoAnalyzer.analyze("linux.iso", it) }
            assertFalse(analysis.isWindows)
            assertEquals(WriteMethod.EXTRACT_FAT32, analysis.recommended)
            assertTrue(analysis.option(WriteMethod.DD).notes.any { it.contains("não é híbrida") })
            assertFalse(analysis.option(WriteMethod.WINDOWS_FAT32).available)

            val img = File(work, "usb.img")
            val report = FileBlockDevice.create(img, 300L shl 20).use { dev ->
                RandomReader.of(iso).use { IsoWriter.write(dev, it, analysis, WriteRequest(WriteMethod.EXTRACT_FAT32)) }
            }
            assertEquals("DISTRO_TEST", report.label)
            assertEquals(files.size, report.filesWritten)
            files.forEach { (p, d) ->
                val f = File(work, "x"); f.delete()
                mcopy(img, "/$p", f)
                assertContentEquals(d, f.readBytes(), p)
            }
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun hybridIsoIsWrittenWithDdAndVerified() {
        val mkrescue = tool("grub-mkrescue") ?: return
        if (tool("xorriso") == null) return
        val work = tempDir("bf-dd")
        try {
            val tree = File(work, "tree/boot/grub").apply { mkdirs() }
            File(tree, "grub.cfg").writeText("set timeout=1\nmenuentry \"BootForge DD\" { halt }\n")
            val iso = File(work, "hybrid.iso")
            run(mkrescue, "-o", iso.path, File(work, "tree").path)
            val analysis = RandomReader.of(iso).use { IsoAnalyzer.analyze("hybrid.iso", it) }
            assertTrue(analysis.info.hybridMbr || analysis.info.hybridGpt, analysis.info.toString())
            assertEquals(WriteMethod.DD, analysis.recommended)

            val img = File(work, "usb.img")
            // simula um pendrive usado: lixo no fim (ex.: GPT de backup antigo)
            FileBlockDevice.create(img, 64L shl 20).use { it.write(it.blockCount - 1, ByteArray(512) { 0x55 }) }
            val report = FileBlockDevice.open(img).use { dev ->
                RandomReader.of(iso).use { IsoWriter.write(dev, it, analysis, WriteRequest(WriteMethod.DD)) }
            }
            assertTrue(report.verified)
            assertEquals(Hashing.hash(iso), report.imageSha256)
            val written = img.readBytes()
            assertContentEquals(iso.readBytes(), written.copyOf(iso.length().toInt()))
            assertTrue(written.takeLast(512).all { it == 0.toByte() }, "fim do disco deveria ter sido apagado")
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun mbrBootCodeFitsAndPartitionRangeIsAligned() {
        val code = IsoWriter.mbrBootCode()
        assertTrue(code.copyOfRange(440, 512).all { it == 0.toByte() })
        val (s, n) = IsoWriter.partitionRange(15_000_000, PartitionScheme.GPT)
        assertEquals(2048, s)
        assertEquals(0, (s + n) % 2048)
        assertTrue(s + n <= 15_000_000 - 33)
        assertEquals("CCCOMA_X64F", IsoWriter.sanitizeLabel("CCCOMA_X64FRE_PT-BR_DV9"))
        assertEquals("UBUNTU_24_0", IsoWriter.sanitizeLabel("Ubuntu 24.04 LTS").replace(' ', '_'))
    }
}
