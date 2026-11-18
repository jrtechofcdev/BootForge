package dev.bootforge.app

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.bootforge.app.ui.DriveReport
import dev.bootforge.app.ui.ImageFile
import dev.bootforge.app.data.OperationState
import dev.bootforge.app.ui.screens.DriveDetails
import dev.bootforge.app.ui.screens.HomeContent
import dev.bootforge.app.ui.screens.InstallForm
import dev.bootforge.app.ui.screens.OperationContent
import dev.bootforge.app.ui.screens.VentoyOverview
import dev.bootforge.app.ui.theme.BfColors
import dev.bootforge.app.ui.theme.BootForgeTheme
import dev.bootforge.blockdev.FileBlockDevice
import dev.bootforge.blockdev.Progress
import dev.bootforge.blockdev.readBlocks
import dev.bootforge.partition.DiskLayoutReader
import dev.bootforge.usb.UsbDriveInfo
import dev.bootforge.ventoy.PartitionStyle
import dev.bootforge.ventoy.VentoyDetector
import dev.bootforge.ventoy.VentoyInstallOptions
import dev.bootforge.ventoy.VentoyInstaller
import dev.bootforge.ventoy.VentoyPackage
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Capturas de tela geradas na JVM (Paparazzi). Os dados do pendrive NÃO são inventados:
 * o Ventoy é realmente instalado em um disco virtual de 16 GiB e as telas exibem o que o
 * detector leu desse disco. Só a identificação USB (fabricante/modelo) é ilustrativa,
 * pois não existe hardware USB no teste.
 *
 * Rodar:  ./gradlew :app:recordPaparazziDebug   (imagens em app/src/test/snapshots/images)
 */
class ScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6.copy(softButtons = false), maxPercentDifference = 0.1)

    private val usb = UsbDriveInfo("/dev/bus/usb/001/002", 0x0781, 0x5583, "SanDisk", "Ultra Fit", "4C530001", true)

    private fun realReport(install: Boolean): DriveReport {
        val file = File.createTempFile("shot", ".img").apply { deleteOnExit() }
        FileBlockDevice.create(file, 16L shl 30).use { dev ->
            if (install) {
                val pkg = VentoyPackage.fromDirectory(File(System.getProperty("bootforge.ventoyDir")))
                VentoyInstaller(pkg).install(dev, VentoyInstallOptions(PartitionStyle.MBR), dev.sizeBytes)
            }
            return DriveReport(
                usb, dev.blockSize, dev.blockCount, DiskLayoutReader.read(dev), VentoyDetector.inspect(dev),
                dev.readBlocks(0, 1), emptyList<ImageFile>(),
            ).also { file.delete() }
        }
    }

    @Composable
    private fun Screen(content: @Composable () -> Unit) = BootForgeTheme {
        Box(Modifier.fillMaxSize().background(BfColors.Background)) { content() }
    }

    @Composable
    private fun Scroll(content: @Composable () -> Unit) =
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }

    @Test
    fun home() = paparazzi.snapshot { Screen { HomeContent(driveCount = 1, onNavigate = {}) } }

    @Test
    fun ventoyOverview() {
        val r = realReport(install = true)
        paparazzi.snapshot { Screen { Scroll { VentoyOverview(r, 1, "1.1.18", {}, {}) } } }
    }

    @Test
    fun driveDetails() {
        val r = realReport(install = true)
        paparazzi.snapshot { Screen { DriveDetails(r, 1, {}, {}, {}) } }
    }

    @Test
    fun installForm() {
        val r = realReport(install = false)
        paparazzi.snapshot { Screen { InstallForm(r, 1) {} } }
    }

    @Test
    fun operationProgress() = paparazzi.snapshot {
        Screen {
            OperationContent(
                OperationState(
                    title = "Instalação do Ventoy 1.1.18",
                    deviceLabel = "SanDisk Ultra Fit",
                    step = "Gravando a partição VTOYEFI (32 MiB)",
                    stepIndex = 3, stepCount = 7,
                    progress = Progress("Gravando", 21L shl 20, 32L shl 20, 9.6 * (1 shl 20)),
                    logs = listOf("Pacote Ventoy 1.1.18 íntegro (SHA-256 conferido)", "exFAT: cluster 32 KiB, 487935 clusters"),
                ),
                onCancel = {}, onClose = {},
            )
        }
    }
}
