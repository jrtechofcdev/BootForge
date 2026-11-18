package dev.bootforge.fs.fat

import dev.bootforge.blockdev.MemoryBlockDevice
import org.tukaani.xz.XZInputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Testa o leitor FAT com a partição VTOYEFI REAL do pacote oficial do Ventoy 1.1.18
 * (arquivo `ventoy/ventoy.disk.img.xz`).
 */
class FatVolumeTest {

    private val ventoyDir = File(System.getProperty("bootforge.ventoyDir"))

    private fun loadEfiImage(): MemoryBlockDevice {
        val bytes = XZInputStream(File(ventoyDir, "ventoy/ventoy.disk.img.xz").inputStream().buffered()).use { it.readBytes() }
        return MemoryBlockDevice(bytes)
    }

    @Test
    fun readsVentoyVersionFromRealVtoyefi() {
        val fat = FatVolume.open(loadEfiImage())
        assertEquals(FatVolume.Type.FAT16, fat.type)
        assertEquals("VTOYEFI", fat.label)
        val cfg = String(fat.readFile("/grub/grub.cfg"), Charsets.UTF_8)
        assertTrue(cfg.contains("set VENTOY_VERSION=\"1.1.18\""))
    }

    @Test
    fun findsSecureBootAndThemeFiles() {
        val fat = FatVolume.open(loadEfiImage())
        assertTrue(fat.exists("/EFI/BOOT/BOOTX64.EFI"))
        assertTrue(fat.exists("/EFI/BOOT/grubx64_real.efi"))
        assertTrue(fat.exists("/grub/themes/ventoy/theme.txt"))
        assertTrue(fat.exists("/ventoy/ventoy.cpio"))
        assertEquals(1045176, fat.resolve("/EFI/BOOT/BOOTX64.EFI")!!.size)
        var count = 0
        fat.walk { _, e -> if (!e.isDirectory) count++ }
        assertTrue(count > 900, "Esperava mais de 900 arquivos, achou $count")
    }
}
