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
import dev.bootforge.app.ui.screens.IsoEditorActions
import dev.bootforge.app.ui.screens.IsoEditorContent
import dev.bootforge.app.ui.EditorItem
import dev.bootforge.app.ui.EditorUi
import dev.bootforge.app.ui.SourceSummary
import dev.bootforge.isoedit.BytesContent
import dev.bootforge.isoedit.EditSession
import dev.bootforge.isoedit.IsoBuilder
import dev.bootforge.isoedit.IsoSource as EditableIso
import dev.bootforge.isoedit.Paths
import dev.bootforge.isoedit.Source
import dev.bootforge.isoedit.VDir
import dev.bootforge.isoedit.VFile
import dev.bootforge.app.ui.screens.IsoWriterActions
import dev.bootforge.app.ui.screens.IsoWriterContent
import dev.bootforge.app.ui.IsoSource
import dev.bootforge.app.ui.IsoWriterUi
import dev.bootforge.app.ui.InspectState
import dev.bootforge.blockdev.RandomReader
import dev.bootforge.isowriter.IsoAnalyzer
import dev.bootforge.app.ui.screens.InstallForm
import dev.bootforge.app.ui.screens.OperationContent
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

    /**
     * Tela "Gravar ISO" com uma análise REAL: uma ISO com a estrutura do instalador do Windows
     * (UDF, bootmgr, efi/boot, sources/boot.wim e install.wim) é gerada com o genisoimage e analisada pelo motor.
     */
    @Test
    fun isoWriter() {
        val work = File.createTempFile("shot-iso", "").apply { delete(); mkdirs() }
        val tree = File(work, "t")
        listOf("bootmgr", "bootmgr.efi", "efi/boot/bootx64.efi", "sources/boot.wim", "sources/install.wim", "setup.exe").forEach {
            File(tree, it).apply { parentFile.mkdirs(); writeBytes(ByteArray(40_000) { i -> (i * 7).toByte() }) }
        }
        val iso = File(work, "Win11_24H2_BrazilianPortuguese_x64.iso")
        ProcessBuilder("genisoimage", "-quiet", "-udf", "-J", "-V", "CCCOMA_X64FRE_PT-BR_DV9", "-o", iso.path, tree.path).start().waitFor()
        val analysis = RandomReader.of(iso).use { IsoAnalyzer.analyze(iso.name, it) }
        work.deleteRecursively()
        val report = realReport(install = true)
        val ui = IsoWriterUi(IsoSource.Ready(android.net.Uri.EMPTY, iso.name, analysis), analysis.recommended, analysis.recommendedScheme, true, usb.deviceName)
        paparazzi.snapshot {
            Screen { IsoWriterContent(ui, listOf(usb), report, InspectState.Ready(report), IsoWriterActions()) }
        }
    }

    /** Editor de ISO com uma imagem REAL do grub-mkrescue aberta pelo motor e duas edições pendentes. */
    @Test
    fun isoEditor() {
        val work = File.createTempFile("shot-ed", "").apply { delete(); mkdirs() }
        File(work, "t/boot/grub").mkdirs()
        File(work, "t/boot/grub/grub.cfg").writeText("set timeout=5\nmenuentry \"Linux\" { linux /vmlinuz }\n")
        File(work, "t/casper").mkdirs(); File(work, "t/casper/filesystem.squashfs").writeBytes(ByteArray(300_000))
        File(work, "t/README.diskdefines").writeText("#define DISKNAME Exemplo")
        val iso = File(work, "ubuntu-exemplo.iso")
        ProcessBuilder("grub-mkrescue", "-o", iso.path, File(work, "t").path).redirectErrorStream(true).start().apply { inputStream.readBytes(); waitFor() }
        val ui = RandomReader.of(iso).use { r ->
            val src = EditableIso.open(r)
            val s = EditSession(src)
            s.replaceText("/boot/grub/grub.cfg", "set timeout=10\n")
            s.addFile("/", "LEIA-ME.txt", BytesContent("BootForge".toByteArray(), "LEIA-ME.txt"))
            val boots = src.bootImages.mapNotNull { it.path }.toSet()
            val items = s.root.children.map { n ->
                val p = Paths.join("/", n.name)
                when (n) {
                    is VDir -> EditorItem(n.name, p, true, 0, false, false, false, n.children.size)
                    is VFile -> EditorItem(n.name, p, false, n.size, n.source is Source.External, p in boots, false, 0)
                }
            }.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
            EditorUi(
                summary = SourceSummary(iso.name, r.size, src.formatLabel, src.volumeId, Paths.countFiles(src.root), Paths.countDirs(src.root),
                    src.bootImages.map { "${it.entry.platformName}: ${it.path}" }, src.isHybrid, IsoBuilder.availability(src), IsoBuilder.recommended(src), src.warnings, src.blockers),
                items = items, history = s.history, canUndo = true, pending = s.pendingChanges(),
            )
        }
        work.deleteRecursively()
        paparazzi.snapshot { Screen { IsoEditorContent(ui, IsoEditorActions()) } }
    }

    @Test
    fun home() = paparazzi.snapshot { Screen { HomeContent(driveCount = 1, onNavigate = {}) } }

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
    fun ventoyHubOverview() {
        val r = realReport(install = true)
        paparazzi.snapshot {
            Screen { dev.bootforge.app.ui.screens.ventoy.OverviewTab(dev.bootforge.app.ui.HubState(usb, r.ventoy, r.capacityBytes), "1.1.18") {} }
        }
    }

    /** Pré-visualização com o tema ORIGINAL do Ventoy (theme.txt e imagens reais do pacote). */
    @Test
    fun themePreview() {
        val dir = File(System.getProperty("bootforge.ventoyDir"), "plugin/ventoy/theme")
        val theme = dev.bootforge.ventoy.GrubTheme.parse(File(dir, "theme.txt").readText())
        val images = listOf("background.png", "menu_c.png", "select_c.png").associateWith { File(dir, it).readBytes() }
        val preview = dev.bootforge.app.ui.ThemePreview("/ventoy/theme/theme.txt", theme, images, listOf("ubuntu-24.04.1-desktop-amd64.iso", "Win11_24H2_BrazilianPortuguese_x64.iso", "debian-live-12.7.0-amd64-kde.iso"))
        paparazzi.snapshot { Screen { Scroll { dev.bootforge.app.ui.screens.ventoy.ThemePreviewCanvas(preview) } } }
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
