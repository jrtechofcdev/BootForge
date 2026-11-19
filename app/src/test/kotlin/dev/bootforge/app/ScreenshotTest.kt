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
import dev.bootforge.app.ui.screens.LogsActions
import dev.bootforge.app.ui.screens.LogsContent
import dev.bootforge.app.data.LogLine
import dev.bootforge.integrity.Diag
import dev.bootforge.app.ui.screens.CatalogActions
import dev.bootforge.app.ui.screens.CatalogContent
import dev.bootforge.app.ui.screens.DownloadsActions
import dev.bootforge.app.ui.screens.DownloadsContent
import dev.bootforge.app.data.CenterState
import dev.bootforge.app.data.EntryState
import dev.bootforge.app.data.JobState
import dev.bootforge.app.data.JobStatus
import dev.bootforge.catalog.CatalogDefinition
import dev.bootforge.catalog.Checksum
import dev.bootforge.catalog.DownloadRequest
import dev.bootforge.catalog.ItemStatus
import dev.bootforge.catalog.LibraryItem
import dev.bootforge.catalog.ResolvedImage
import dev.bootforge.catalog.Verification
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
import dev.bootforge.app.ui.MediaTool
import dev.bootforge.app.ui.ToolsUi
import dev.bootforge.app.ui.screens.SurfaceResult
import dev.bootforge.app.ui.screens.ToolsActions
import dev.bootforge.app.ui.screens.ToolsContent
import dev.bootforge.blockdev.MemoryBlockDevice
import dev.bootforge.mediatools.SurfaceTest
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

    /**
     * Catálogo e Downloads. Versões, tamanhos e checksums abaixo são os retornados pelas fontes
     * oficiais na consulta real de 18/11/2026 (teste LiveCatalogTest).
     */
    private fun centerState(): CenterState {
        val def = CatalogDefinition.bundled()
        fun sys(id: String) = def.systems.first { it.id == id }
        val resolved = mapOf(
            "windows11" to ResolvedImage(sys("windows11"), null, null, null, null, null, Verification.NONE, notes = sys("windows11").notes),
            "ubuntu-desktop" to ResolvedImage(sys("ubuntu-desktop"), "26.04.1", "https://releases.ubuntu.com/26.04.1/ubuntu-26.04.1-desktop-amd64.iso", "ubuntu-26.04.1-desktop-amd64.iso", 6482409472, Checksum("SHA-256", "601e30fbf5d97759367c632e2c33630665039b7e2158fd068403da3ccf1bda1f", ""), Verification.SIGNED, "843938DF228D22F7B3742BC0D94AA3F0EFE21092"),
            "linuxmint-cinnamon" to ResolvedImage(sys("linuxmint-cinnamon"), "22.3", "https://mirrors.edge.kernel.org/linuxmint/stable/22.3/linuxmint-22.3-cinnamon-64bit.iso", "linuxmint-22.3-cinnamon-64bit.iso", 3091660800, Checksum("SHA-256", "a081ab202cfda17f6924128dbd2de8b63518ac0531bcfe3f1a1b88097c459bd4", ""), Verification.SIGNED),
            "fedora-workstation" to ResolvedImage(sys("fedora-workstation"), "45", "https://download.fedoraproject.org/pub/fedora/linux/releases/45/Workstation/x86_64/iso/Fedora-Workstation-Live-45-1.4.x86_64.iso", "Fedora-Workstation-Live-45-1.4.x86_64.iso", 2925092864, Checksum("SHA-256", "68338c6bfe4f0d64151f2e47efe9f44a8855ad05b460e56b844fb3fa2c9a3beb", ""), Verification.OFFICIAL_CHECKSUM),
        )
        val entries = def.systems.associate { it.id to EntryState(it, resolved[it.id]) }
        val job = JobState(DownloadRequest.of(resolved.getValue("linuxmint-cinnamon")), JobStatus.RUNNING, 1_236_664_320,
            Progress("Baixando", 1_236_664_320, 3091660800, 6_500_000.0))
        // ISO real do Alpine baixada e conferida nesta etapa (SHA-256 e tamanho verdadeiros)
        val lib = LibraryItem("alpine-virt-3.20.3-x86_64.iso", "Alpine Linux", "3.20.3", "alpine-standard", 63963136,
            "81df854fbd7327d293c726b1eeeb82061d3bc8f5a86a6f77eea720f6be372261", null, ItemStatus.CHECKSUM_OK, null, null, 1_795_000_000_000)
        return CenterState(def, "embutido no app (${def.updated})", entries, listOf(job), listOf(lib), checkedAt = 1_795_000_000_000)
    }

    @Test
    fun catalog() = paparazzi.snapshot { Screen { CatalogContent(centerState(), null, CatalogActions()) } }

    @Test
    fun downloads() = paparazzi.snapshot { Screen { DownloadsContent(centerState(), "/storage/emulated/0/Android/data/dev.bootforge.app/files/isos · livre: 41,2 GB", DownloadsActions()) } }

    /**
     * Tela de logs. As linhas usam exatamente as mensagens que o app registra (UsbDriveManager,
     * OperationRunner, UsbBlockDevice) num cenário de exemplo: pendrive removido durante uma gravação.
     */
    @Test
    fun logs() {
        val t = 1_795_000_000_000
        val lines = listOf(
            LogLine(t, Diag.Level.INFO, "USB", "Conectado: 0781:5583 /dev/bus/usb/001/002 [if0 classe=8 sub=6 prot=0x50 ep=2]"),
            LogLine(t + 2_000, Diag.Level.INFO, "USB", "Abrindo 0781:5583 \"SanDisk Ultra Fit\" série •••••••0001 (leitura e escrita)"),
            LogLine(t + 2_400, Diag.Level.INFO, "USB", "Pronto: setor 512 bytes × 30031872 setores = 15376318464 bytes"),
            LogLine(t + 5_000, Diag.Level.INFO, "Operação", "INÍCIO: Gravar ISO: debian-13.7.0-amd64-netinst.iso — SanDisk Ultra Fit"),
            LogLine(t + 5_100, Diag.Level.INFO, "Operação", "Etapa 1/1: Imagem direta (DD)"),
            LogLine(t + 61_000, Diag.Level.WARN, "USB", "Removido: 0781:5583 /dev/bus/usb/001/002"),
            LogLine(t + 61_050, Diag.Level.ERROR, "SCSI", "Falha de GRAVAÇÃO no LBA 1048576 (2048 setores)\njava.io.IOException: Could not write to device, result == -1 errno 19 No such device"),
            LogLine(t + 61_100, Diag.Level.ERROR, "Operação", "FALHOU: Gravar ISO: debian-13.7.0-amd64-netinst.iso — Falha de leitura/gravação ou pendrive desconectado (progresso: 536870912/792723456 bytes, etapa: Imagem direta (DD))"),
        ).reversed()
        paparazzi.snapshot { Screen { LogsContent(lines, 184_320, 0, false, null, null, 64, LogsActions()) } }
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

    /** Ferramentas → Formatar, com o pendrive lido de um disco virtual onde o Ventoy foi instalado de verdade. */
    @Test
    fun tools() {
        val report = realReport(install = true)
        paparazzi.snapshot {
            Screen { ToolsContent(ToolsUi(MediaTool.FORMAT, usb.deviceName), listOf(usb), report, InspectState.Ready(report), ToolsActions()) }
        }
    }

    /**
     * Resultado do teste de superfície produzido pelo motor REAL sobre um pendrive falso simulado
     * (anuncia 96 MB, guarda 40 MB e descarta o resto).
     */
    @Test
    fun surfaceResult() {
        val mem = MemoryBlockDevice(96 shl 20)
        val limit = (40L shl 20) / 512
        val fake = object : dev.bootforge.blockdev.BlockDevice by mem {
            override fun write(lba: Long, buffer: ByteArray, offset: Int, length: Int) { if (lba < limit) mem.write(lba, buffer, offset, length) }
        }
        val measured = SurfaceTest.run(fake)
        // Capacidade real e trechos ruins vêm do motor. As velocidades de um disco em MEMÓRIA variam a
        // cada execução (e não significam nada), então, para a captura ser reproduzível, usamos um perfil
        // fixo típico de pendrive USB 2.0 (gravação 18 MB/s, leitura 32 MB/s), caindo após o limite real.
        val realRegions = (measured.realBytes / (96L shl 20).toDouble() * measured.regions.size).toInt()
        val r = measured.copy(
            regions = measured.regions.map { g ->
                val beyond = g.index >= realRegions
                g.copy(writeBytesPerSec = if (beyond) 4.0e6 else 18.0e6 + (g.index % 7) * 4e5, readBytesPerSec = 32.0e6 - (g.index % 5) * 6e5)
            },
            writeBytesPerSec = 11.0e6, readBytesPerSec = 31.0e6,
        )
        paparazzi.snapshot { Screen { Scroll { SurfaceResult(r) } } }
    }
}
