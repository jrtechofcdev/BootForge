package dev.bootforge.isowriter

import dev.bootforge.blockdev.FaultInjectingBlockDevice
import dev.bootforge.blockdev.FileBlockDevice
import dev.bootforge.blockdev.RandomReader
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Etapa 7: pendrive "arrancado" no meio da gravação de ISO — tem de falhar, nunca concluir. */
class StabilityTest {
    private fun tool(n: String) = listOf("/usr/bin/$n", "/usr/sbin/$n").firstOrNull { File(it).exists() }

    @Test
    fun ddInterruptedFailsAndVerificationCatchesSilentCorruption() {
        val work = File.createTempFile("bf-st-dd", "").apply { delete(); mkdirs() }
        try {
            val iso = File(work, "img.iso").apply { writeBytes(Random(5).nextBytes(6_000_000)) }
            val analysis = RandomReader.of(iso).use { IsoAnalyzer.analyze("img.iso", it) }
            for (cut in listOf(512L, 1_000_000L, 5_999_000L)) {
                val inner = FileBlockDevice.create(File(work, "usb-$cut.img"), 64L shl 20)
                val dev = FaultInjectingBlockDevice(inner, failAfterWrittenBytes = cut)
                assertFailsWith<IOException>("corte em $cut") {
                    RandomReader.of(iso).use { IsoWriter.write(dev, it, analysis, WriteRequest(WriteMethod.DD)) }
                }
                inner.close()
            }
            // pendrive que "mente": aceita a gravação mas devolve outro conteúdo na leitura
            val liar = object : dev.bootforge.blockdev.BlockDevice by FileBlockDevice.create(File(work, "liar.img"), 64L shl 20) {
                override fun read(lba: Long, buffer: ByteArray, offset: Int, length: Int) {
                    java.util.Arrays.fill(buffer, offset, offset + length, 0x5A)
                }
            }
            val e = assertFailsWith<IOException> { RandomReader.of(iso).use { IsoWriter.write(liar, it, analysis, WriteRequest(WriteMethod.DD)) } }
            assertTrue(e.message!!.contains("Verificação falhou"), e.message)
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun fat32WriteInterruptedFails() {
        val geniso = tool("genisoimage") ?: return
        val work = File.createTempFile("bf-st-fat", "").apply { delete(); mkdirs() }
        try {
            val tree = File(work, "t")
            File(tree, "EFI/BOOT").mkdirs(); File(tree, "EFI/BOOT/BOOTX64.EFI").writeBytes(Random(1).nextBytes(500_000))
            File(tree, "live").mkdirs(); File(tree, "live/fs.squashfs").writeBytes(Random(2).nextBytes(8_000_000))
            val iso = File(work, "l.iso")
            ProcessBuilder(geniso, "-quiet", "-R", "-J", "-o", iso.path, tree.path).start().apply { waitFor(60, TimeUnit.SECONDS) }
            val analysis = RandomReader.of(iso).use { IsoAnalyzer.analyze("l.iso", it) }
            for (cut in listOf(4096L, 2_000_000L, 7_000_000L)) {
                val inner = FileBlockDevice.create(File(work, "u$cut.img"), 300L shl 20)
                val dev = FaultInjectingBlockDevice(inner, failAfterWrittenBytes = cut)
                assertFailsWith<IOException>("corte em $cut") {
                    RandomReader.of(iso).use { IsoWriter.write(dev, it, analysis, WriteRequest(WriteMethod.EXTRACT_FAT32)) }
                }
                // a tabela de partições é gravada por ÚLTIMO: pendrive interrompido não parece "pronto"
                val mbr = ByteArray(512).also { inner.read(0, it) }
                assertTrue(mbr.copyOfRange(0x1BE, 0x1FE).all { it == 0.toByte() }, "corte em $cut deixou tabela de partições")
                inner.close()
            }
        } finally {
            work.deleteRecursively()
        }
    }
}
