package dev.bootforge.fs.exfat

import dev.bootforge.blockdev.FileBlockDevice
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExFatTest {

    @Test
    fun upcaseTableIsTheStandardOne() {
        // Checksum da tabela recomendada pela especificação exFAT
        assertEquals(5836, ExFatFormatter.upcaseTable.size)
        assertEquals(0xE619D30DL, ExFatFormatter.tableChecksum(ExFatFormatter.upcaseTable))
    }

    @Test
    fun formatThenReadBack() {
        val file = File.createTempFile("exfat", ".img").apply { deleteOnExit() }
        FileBlockDevice.create(file, 256L shl 20).use { dev ->
            val g = ExFatFormatter.format(dev, ExFatFormatter.Options("Ventoy", 32 * 1024, partitionOffsetLba = 2048))
            val vol = ExFatVolume.open(dev)
            assertTrue(vol.bootChecksumValid)
            assertEquals("Ventoy", vol.label)
            assertEquals(g.clusterCount, vol.clusterCount)
            assertTrue(vol.list().isEmpty())
            // Apenas bitmap + up-case + raiz ocupados
            assertEquals(g.clusterCount - (g.rootCluster - 1), vol.freeClusters())
        }
        fsckIfAvailable(file)
    }

    @Test
    fun ventoyClusterRule() {
        assertEquals(32 * 1024, ExFatFormatter.ventoyClusterSize(32L shl 30))
        assertEquals(128 * 1024, ExFatFormatter.ventoyClusterSize(64L shl 30))
    }

    /** Valida com o fsck.exfat oficial (exfatprogs), quando instalado na máquina. */
    private fun fsckIfAvailable(file: File) {
        val fsck = listOf("/usr/sbin/fsck.exfat", "/sbin/fsck.exfat").map(::File).firstOrNull { it.exists() } ?: return
        val p = ProcessBuilder(fsck.path, "-n", file.path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(60, TimeUnit.SECONDS)
        assertEquals(0, p.exitValue(), "fsck.exfat reprovou o volume:\n$out")
    }
}
