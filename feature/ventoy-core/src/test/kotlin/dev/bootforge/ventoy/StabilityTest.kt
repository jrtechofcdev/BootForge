package dev.bootforge.ventoy

import dev.bootforge.blockdev.FaultInjectingBlockDevice
import dev.bootforge.blockdev.FileBlockDevice
import dev.bootforge.blockdev.slice
import dev.bootforge.fs.exfat.ExFatVolume
import dev.bootforge.integrity.Hashing
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Etapa 7 — estabilidade: o pendrive é "arrancado" (simulação) no meio de cada operação de
 * escrita. Em todos os casos a operação precisa FALHAR com erro (nunca relatar sucesso) e o
 * que já estava no pendrive (ISOs, arquivos) não pode ser corrompido.
 */
class StabilityTest {
    private val pkg = VentoyPackage.fromDirectory(File(System.getProperty("bootforge.ventoyDir")))
    private val temp = mutableListOf<File>()

    @AfterTest
    fun cleanup() = temp.forEach { it.delete() }

    private fun disk(mib: Long = 512): FileBlockDevice {
        val f = File.createTempFile("bf-st", ".img").also { temp += it }
        return FileBlockDevice.create(f, mib shl 20)
    }

    @Test
    fun interruptedInstallNeverReportsSuccess() {
        // quanto uma instalação completa grava (para cortar em pontos proporcionais)
        val total = disk().let { d -> FaultInjectingBlockDevice(d).also { VentoyInstaller(pkg).install(it, VentoyInstallOptions(), it.sizeBytes) }.written.also { d.close() } }
        for (cut in listOf(512L) + listOf(0.01, 0.10, 0.50, 0.90, 0.99).map { (total * it).toLong() }) {
            val inner = disk()
            val dev = FaultInjectingBlockDevice(inner, failAfterWrittenBytes = cut)
            assertFailsWith<IOException>("corte em $cut bytes") { VentoyInstaller(pkg).install(dev, VentoyInstallOptions(), dev.sizeBytes) }
            // "reconectado": a leitura do que sobrou não pode travar nem dizer que está tudo certo
            val info = VentoyDetector.inspect(inner, scanData = true)
            assertTrue(!info.isVentoy || info.checks.any { !it.passed } || info.warnings.isNotEmpty() || cut > total * 0.95, "corte em $cut de $total: $info")
            // e uma nova instalação no mesmo pendrive funciona normalmente
            val ok = VentoyInstaller(pkg).install(inner, VentoyInstallOptions(), inner.sizeBytes)
            assertTrue(ok.detected.isVentoy)
            inner.close()
        }
    }

    @Test
    fun interruptedUpdateKeepsIsosIntactAndCanBeRetried() {
        val inner = disk()
        val r = VentoyInstaller(pkg).install(inner, VentoyInstallOptions(), inner.sizeBytes)
        val iso = Random(1).nextBytes(3_000_000)
        val data = inner.slice(r.detected.part1Start!!, r.detected.part1Sectors!!)
        ExFatVolume.open(data, writable = true).writeFile("/linux.iso", ByteArrayInputStream(iso), iso.size.toLong())

        for (cut in listOf(4096L, 1_000_000L, 10_000_000L)) {
            val dev = FaultInjectingBlockDevice(inner, failAfterWrittenBytes = cut)
            assertFailsWith<IOException> { VentoyUpdater(pkg).update(dev, dev.sizeBytes) }
            val vol = ExFatVolume.open(inner.slice(r.detected.part1Start!!, r.detected.part1Sectors!!))
            assertContentEquals(iso, vol.openFile("/linux.iso").use { it.readBytes() }, "ISO intacta após corte em $cut")
            assertTrue(vol.checkConsistency().errors.isEmpty())
        }
        // depois de "reconectar", a atualização completa funciona e a ISO continua lá
        val up = VentoyUpdater(pkg).update(inner, inner.sizeBytes)
        assertTrue(up.detected.isVentoy)
        val vol = ExFatVolume.open(inner.slice(r.detected.part1Start!!, r.detected.part1Sectors!!))
        assertEquals(Hashing.hash(iso), vol.openFile("/linux.iso").use { Hashing.hash(it) })
        inner.close()
    }

    @Test
    fun interruptedCopyToVentoyDoesNotCorruptExistingFiles() {
        val inner = disk()
        val r = VentoyInstaller(pkg).install(inner, VentoyInstallOptions(), inner.sizeBytes)
        val start = r.detected.part1Start!!; val count = r.detected.part1Sectors!!
        val existing = Random(2).nextBytes(2_000_000)
        ExFatVolume.open(inner.slice(start, count), writable = true).writeFile("/ubuntu.iso", ByteArrayInputStream(existing), existing.size.toLong())

        val big = Random(3).nextBytes(8_000_000)
        val faulty = FaultInjectingBlockDevice(inner, failAfterWrittenBytes = 3_000_000)
        assertFailsWith<IOException> {
            ExFatVolume.open(faulty.slice(start, count), writable = true).writeFile("/windows.iso", ByteArrayInputStream(big), big.size.toLong())
        }
        val vol = ExFatVolume.open(inner.slice(start, count))
        assertContentEquals(existing, vol.openFile("/ubuntu.iso").use { it.readBytes() })
        assertNull(vol.resolve("/windows.iso"), "arquivo incompleto não pode aparecer como se estivesse pronto")
        val check = vol.checkConsistency()
        assertTrue(check.errors.isEmpty(), "erros: ${check.errors}")
        println("Avisos após a interrupção (esperado: espaço reservado sem dono ou volume marcado como 'sujo'): ${check.warnings}")
        inner.close()
    }
}
