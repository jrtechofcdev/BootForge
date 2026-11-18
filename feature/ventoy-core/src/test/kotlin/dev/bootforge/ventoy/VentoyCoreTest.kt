package dev.bootforge.ventoy

import dev.bootforge.blockdev.FileBlockDevice
import dev.bootforge.blockdev.readBlocks
import dev.bootforge.partition.FsType
import dev.bootforge.partition.Mbr
import dev.bootforge.partition.PartitionScheme
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VentoyCoreTest {

    private val pkg = VentoyPackage.fromDirectory(File(System.getProperty("bootforge.ventoyDir")))

    @Test
    fun packageIsTheOfficialOne() {
        assertEquals("1.1.18", pkg.version)
        assertTrue(pkg.verifyIntegrity().isEmpty())
        assertEquals(1048064, pkg.coreImg().size)
    }

    /**
     * O cálculo do Windows (VentoyFillMBR/VentoyFillGpt) e o do Linux (ventoy_lib.sh)
     * são escritos de forma diferente; aqui provamos que o nosso coincide com os DOIS
     * para vários tamanhos de disco "tortos".
     */
    @Test
    fun layoutMatchesOfficialLinuxScriptFormula() {
        val sizes = listOf(15_633_408L, 30_031_250L, 60_437_492L, 121_634_817L, 250_069_679L, 4_294_967_295L)
        for (n in sizes) {
            val mbr = VentoyLayout.compute(n, VentoyInstallOptions(PartitionStyle.MBR))
            var p2 = n - 65536            // format_ventoy_disk_mbr: part1_end = N - 65536 - 1
            p2 -= p2 % 8
            assertEquals(p2, mbr.part2Start, "MBR, $n setores")
            assertEquals(0L, mbr.part2Start % 8)

            val gpt = VentoyLayout.compute(n, VentoyInstallOptions(PartitionStyle.GPT))
            var g2 = n - 65536 - 34 + 1   // format_ventoy_disk_gpt: part1_end = N - 65536 - 34
            g2 -= g2 % 8
            assertEquals(g2, gpt.part2Start, "GPT, $n setores")
            assertTrue(gpt.part2End <= n - 34, "VTOYEFI não pode invadir a GPT de backup")
        }
    }

    @Test
    fun mbrRefusesDisksOver2TiB() {
        assertFailsWith<IllegalArgumentException> {
            VentoyLayout.compute(0x1_0000_0000L, VentoyInstallOptions(PartitionStyle.MBR))
        }
    }

    @Test
    fun installMbrAndDetect() = withDisk(512L shl 20) { file, dev ->
        val report = VentoyInstaller(pkg).install(dev, VentoyInstallOptions(PartitionStyle.MBR), dev.sizeBytes)
        val info = report.detected
        assertTrue(info.isVentoy)
        assertEquals("1.1.18", info.version)
        assertEquals(true, info.secureBootSupport)
        assertEquals(PartitionScheme.MBR, info.layout.scheme)
        val p1 = info.layout.partitions[0]
        assertTrue(p1.bootable)
        assertEquals(0x07, p1.mbrType)
        assertEquals(0xEF, info.layout.partitions[1].mbrType)
        assertEquals(FsType.EXFAT, p1.fs?.type)
        assertEquals(FsType.FAT16, info.layout.partitions[1].fs?.type)
        assertEquals(0, info.data?.isoCount)

        // O código de boot gravado é o boot.img original (exceto UUID/assinatura/tabela).
        val s0 = dev.readBlocks(0, 1)
        assertContentEquals(pkg.bootImg().copyOfRange(0, 0x180), s0.copyOfRange(0, 0x180))
        assertContentEquals(s0.copyOfRange(0x180, 0x184), s0.copyOfRange(0x1B8, 0x1BC))
        // core.img no setor 1
        assertContentEquals(pkg.coreImg(), dev.readBlocks(1, 2047).copyOf(1048064))

        externalChecks(file, dev, info)
    }

    @Test
    fun installGptAndDetect() = withDisk(512L shl 20) { file, dev ->
        val report = VentoyInstaller(pkg).install(dev, VentoyInstallOptions(PartitionStyle.GPT), dev.sizeBytes)
        val info = report.detected
        assertTrue(info.isVentoy)
        assertEquals(PartitionScheme.GPT, info.layout.scheme)
        assertEquals(true, info.layout.gptEntriesCrcValid)
        assertEquals(true, info.layout.backupGptValid)
        assertEquals("Ventoy", info.layout.partitions[0].name)
        assertEquals("VTOYEFI", info.layout.partitions[1].name)
        assertEquals(VentoyInstaller.VENTOY_EFI_PART_ATTR, info.layout.partitions[1].gptAttributes)
        assertTrue(info.warnings.isEmpty(), info.warnings.toString())
        val s0 = dev.readBlocks(0, 1)
        assertEquals(0x22, s0[92].toInt())
        assertTrue(Mbr(s0).isProtectiveForGpt)
        val core = dev.readBlocks(34, 2014)
        assertEquals(35, core[500].toInt())
        externalChecks(file, dev, info)
    }

    @Test
    fun updatePreservesDataPartition() = withDisk(512L shl 20) { _, dev ->
        val report = VentoyInstaller(pkg).install(dev, VentoyInstallOptions(PartitionStyle.MBR), dev.sizeBytes)
        val l = report.layout
        // Simula conteúdo do usuário em toda a área de dados após o exFAT
        val marker = ByteArray(1 shl 20) { (it * 31).toByte() }
        dev.write(l.part1Start + 8192, marker)
        val uuidBefore = dev.readBlocks(0, 1).copyOfRange(0x180, 0x190)
        val p1HashBefore = dev.blockdevHash(l.part1Start, l.part1Sectors)
        val up = VentoyUpdater(pkg).update(dev, dev.sizeBytes)
        assertEquals("1.1.18", up.toVersion)
        assertTrue(up.detected.isVentoy)
        assertContentEquals(uuidBefore, dev.readBlocks(0, 1).copyOfRange(0x180, 0x190))
        assertEquals(p1HashBefore, dev.blockdevHash(l.part1Start, l.part1Sectors), "A partição de dados foi alterada!")
    }

    @Test
    fun plainDiskIsNotVentoy() = withDisk(64L shl 20) { _, dev ->
        val info = VentoyDetector.inspect(dev)
        assertFalse(info.isVentoy)
    }

    @Test
    fun confirmationMustMatchDevice() = withDisk(256L shl 20) { _, dev ->
        assertFailsWith<IllegalStateException> {
            VentoyInstaller(pkg).install(dev, VentoyInstallOptions(), dev.sizeBytes - 512)
        }
    }

    // ------------------------------------------------------------------ utilitários

    private fun dev.bootforge.blockdev.BlockDevice.blockdevHash(start: Long, count: Long) =
        dev.bootforge.blockdev.BlockTransfer.hashRegion(this, start, count * blockSize, "hash")

    private fun withDisk(size: Long, body: (File, FileBlockDevice) -> Unit) {
        val file = File.createTempFile("ventoy", ".img")
        try {
            FileBlockDevice.create(file, size).use { body(file, it) }
        } finally {
            file.delete()
        }
    }

    /** Valida com ferramentas oficiais do Linux, quando disponíveis (fsck.fat, fsck.exfat, sfdisk). */
    private fun externalChecks(file: File, dev: FileBlockDevice, info: VentoyInfo) {
        val p1 = info.layout.partitions[0]
        val p2 = info.layout.partitions[1]
        fun extract(start: Long, count: Long): File {
            val out = File.createTempFile("part", ".img")
            FileBlockDevice.create(out, count * 512).use { o ->
                val buf = ByteArray(1 shl 20)
                var done = 0L
                while (done < count) {
                    val n = minOf(2048L, count - done).toInt()
                    dev.read(start + done, buf, 0, n * 512); o.write(done, buf, 0, n * 512); done += n
                }
            }
            return out
        }
        tool("fsck.fat")?.let { fsck ->
            val part = extract(p2.startLba, p2.sectorCount)
            try { run(fsck, "-n", part.path) } finally { part.delete() }
        }
        tool("fsck.exfat")?.let { fsck ->
            val part = extract(p1.startLba, p1.sectorCount)
            try { run(fsck, "-n", part.path) } finally { part.delete() }
        }
        tool("sfdisk")?.let { sfdisk ->
            val out = run(sfdisk, "--dump", file.path)
            assertTrue(out.contains("start=        ${p1.startLba}") || out.contains("start=${p1.startLba}") || out.contains("start=     ${p1.startLba}"), out)
        }
        assertNotNull(info.diskUuid)
    }

    private fun tool(name: String): String? =
        listOf("/usr/sbin/$name", "/sbin/$name", "/usr/bin/$name").firstOrNull { File(it).exists() }

    private fun run(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(120, TimeUnit.SECONDS)
        assertEquals(0, p.exitValue(), "${cmd.joinToString(" ")} falhou:\n$out")
        return out
    }
}
