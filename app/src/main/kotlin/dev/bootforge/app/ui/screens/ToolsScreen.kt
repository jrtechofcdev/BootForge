package dev.bootforge.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bootforge.app.data.Format
import dev.bootforge.app.ui.AppViewModel
import dev.bootforge.app.ui.ChecksumSource
import dev.bootforge.app.ui.DriveReport
import dev.bootforge.app.ui.InspectState
import dev.bootforge.app.ui.MediaTool
import dev.bootforge.app.ui.ToolsUi
import dev.bootforge.app.ui.ToolsViewModel
import dev.bootforge.app.ui.components.Badge
import dev.bootforge.app.ui.components.BfTopBar
import dev.bootforge.app.ui.components.ConfirmWriteDialog
import dev.bootforge.app.ui.components.InfoRow
import dev.bootforge.app.ui.components.Notice
import dev.bootforge.app.ui.components.SectionCard
import dev.bootforge.app.ui.theme.BfColors
import dev.bootforge.app.ui.theme.MonoStyle
import dev.bootforge.integrity.HashAlgorithm
import dev.bootforge.mediatools.Checksums
import dev.bootforge.mediatools.EraseMode
import dev.bootforge.mediatools.MediaFs
import dev.bootforge.mediatools.SurfaceTest
import dev.bootforge.partition.FsType
import dev.bootforge.usb.UsbDriveInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ações da tela Ferramentas (a tela não guarda estado de negócio). */
class ToolsActions(
    val onTool: (MediaTool) -> Unit = {},
    val onTarget: (String) -> Unit = {},
    val onPermission: (String) -> Unit = {},
    val onFs: (MediaFs) -> Unit = {},
    val onGpt: (Boolean) -> Unit = {},
    val onLabel: (String) -> Unit = {},
    val onErase: (EraseMode) -> Unit = {},
    val onPickRestore: () -> Unit = {},
    val onPickChecksumFile: () -> Unit = {},
    val onPickChecksumDrive: (String, Long) -> Unit = { _, _ -> },
    val onExpected: (String) -> Unit = {},
    val onRun: () -> Unit = {},
)

private val MediaTool.icon: ImageVector
    get() = when (this) {
        MediaTool.FORMAT -> Icons.Filled.CleaningServices
        MediaTool.SURFACE -> Icons.Filled.GridOn
        MediaTool.ERASE -> Icons.Filled.DeleteForever
        MediaTool.BACKUP -> Icons.Filled.Backup
        MediaTool.RESTORE -> Icons.Filled.Restore
        MediaTool.CHECKSUM -> Icons.Filled.Fingerprint
    }

/** Ligação com os ViewModels e com os seletores de arquivo do Android. */
@Composable
fun ToolsScreen(vm: AppViewModel, tvm: ToolsViewModel, onBack: () -> Unit) {
    val ui by tvm.ui.collectAsStateWithLifecycle()
    val drives by vm.drives.drives.collectAsStateWithLifecycle()
    val inspect by vm.inspect.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    val target = (inspect as? InspectState.Ready)?.report?.takeIf { it.info.deviceName == ui.target }
    val targetState = inspect.takeIf { s ->
        when (s) {
            is InspectState.Loading -> s.deviceName == ui.target
            is InspectState.Failed -> s.deviceName == ui.target
            is InspectState.Ready -> s.report.info.deviceName == ui.target
            else -> false
        }
    }
    val pickRestore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(tvm::pickRestore) }
    val pickChecksum = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(tvm::pickChecksumFile) }
    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) { uri ->
        if (uri != null && target != null) tvm.backup(target.info, uri)
    }

    Column(Modifier.fillMaxSize()) {
        BfTopBar("Ferramentas", onBack, "Formatar, testar, apagar, backup e checksum")
        ToolsContent(
            ui, drives, target, targetState,
            ToolsActions(
                onTool = tvm::selectTool,
                onTarget = { name -> tvm.selectTarget(name); vm.inspect(name) },
                onPermission = { vm.drives.requestPermission(it) },
                onFs = tvm::setFs, onGpt = tvm::setGpt, onLabel = tvm::setLabel, onErase = tvm::setErase,
                onPickRestore = { pickRestore.launch(arrayOf("*/*")) },
                onPickChecksumFile = { pickChecksum.launch(arrayOf("*/*")) },
                onPickChecksumDrive = tvm::pickChecksumDrive,
                onExpected = tvm::setExpected,
                onRun = {
                    when (ui.tool) {
                        MediaTool.BACKUP -> createBackup.launch("pendrive-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date()) + ".img.gz")
                        MediaTool.CHECKSUM -> tvm.checksum(target?.info, target?.ventoyDataPartition())
                        else -> confirm = true
                    }
                },
            ),
        )
    }

    if (confirm && target != null) {
        val t = ui.tool
        ConfirmWriteDialog(
            title = "${t.title}?",
            report = target,
            connectedDrives = drives.size,
            destructive = true,
            message = when (t) {
                MediaTool.FORMAT -> "O pendrive será formatado em ${ui.fs.title} (${if (ui.gpt) "GPT" else "MBR"}), rótulo \"${ui.label}\"."
                MediaTool.SURFACE -> "O pendrive inteiro será gravado e relido (${Format.bytes(target.capacityBytes)}). Pode levar horas em pendrives grandes."
                MediaTool.ERASE -> "Apagamento ${ui.erase.title.lowercase()}: ${ui.erase.summary}"
                MediaTool.RESTORE -> "A imagem \"${ui.restoreFile?.name}\" será gravada a partir do início do pendrive."
                else -> ""
            },
            confirmLabel = t.title,
            onConfirm = {
                confirm = false
                when (t) {
                    MediaTool.FORMAT -> tvm.format(target.info, target.capacityBytes)
                    MediaTool.SURFACE -> tvm.surfaceTest(target.info, target.capacityBytes)
                    MediaTool.ERASE -> tvm.erase(target.info, target.capacityBytes)
                    MediaTool.RESTORE -> tvm.restore(target.info, target.capacityBytes)
                    else -> Unit
                }
            },
            onDismiss = { confirm = false },
        )
    }
}

/** Partição de dados do Ventoy (exFAT) deste pendrive, se houver. */
fun DriveReport.ventoyDataPartition(): Pair<Long, Long>? {
    if (!ventoy.isVentoy || ventoy.data?.fsType != FsType.EXFAT) return null
    val s = ventoy.part1Start ?: return null
    val c = ventoy.part1Sectors ?: return null
    return s to c
}

/** Conteúdo da tela (sem estado: tudo chega por parâmetro — usado também nas capturas de teste). */
@Composable
fun ToolsContent(
    ui: ToolsUi,
    drives: List<UsbDriveInfo>,
    target: DriveReport?,
    targetState: InspectState?,
    actions: ToolsActions,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(title = "1. Ferramenta", icon = Icons.Filled.Tune) {
            MediaTool.entries.forEach { t -> ToolCard(t, ui.tool == t) { actions.onTool(t) } }
        }

        val needsDrive = !(ui.tool == MediaTool.CHECKSUM && ui.checksumSource is ChecksumSource.Phone)
        SectionCard(title = "2. Opções", icon = ui.tool.icon) { Options(ui, target, actions) }

        SectionCard(title = if (ui.tool == MediaTool.CHECKSUM) "3. Pendrive (opcional)" else "3. Pendrive", icon = Icons.Filled.Usb) {
            if (drives.isEmpty()) Notice("Nenhum pendrive conectado. Conecte pelo adaptador USB OTG.")
            drives.forEach { d ->
                Row(Modifier.fillMaxWidth().selectable(ui.target == d.deviceName, enabled = d.hasPermission, onClick = { actions.onTarget(d.deviceName) }), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(ui.target == d.deviceName, { actions.onTarget(d.deviceName) }, enabled = d.hasPermission)
                    Column(Modifier.weight(1f)) {
                        Text(d.displayName, style = MaterialTheme.typography.bodyMedium)
                        if (!d.hasPermission) Text("Sem permissão de acesso", style = MaterialTheme.typography.bodySmall, color = BfColors.Warning)
                    }
                    if (!d.hasPermission) TextButton(onClick = { actions.onPermission(d.deviceName) }) { Text("Permitir") }
                }
            }
            when (targetState) {
                is InspectState.Loading -> Loading("Lendo o pendrive…")
                is InspectState.Failed -> Notice(targetState.message, BfColors.Danger)
                else -> Unit
            }
            if (target != null) {
                InfoRow("Capacidade", Format.bytes(target.capacityBytes))
                InfoRow("Conteúdo atual", target.layout.partitions.joinToString { "${it.fs?.type?.label ?: "?"} ${it.fs?.label ?: ""}".trim() }.ifEmpty { "sem partições" })
                if (target.blockSize != 512 && ui.tool == MediaTool.FORMAT) Notice("Setor de ${target.blockSize} bytes não suportado para formatar.", BfColors.Danger)
            }
        }

        ui.surface?.takeIf { ui.tool == MediaTool.SURFACE }?.let { SurfaceResult(it) }
        if (ui.tool == MediaTool.CHECKSUM) ui.checksum?.let { ChecksumResult(it.source, it.values, ui.expected) }

        val ready = when (ui.tool) {
            MediaTool.FORMAT -> target != null && target.blockSize == 512
            MediaTool.RESTORE -> target != null && ui.restoreFile != null
            MediaTool.CHECKSUM -> ui.checksumSource != null && (!needsDrive || target != null)
            else -> target != null
        }
        Button(
            onClick = actions.onRun,
            enabled = ready,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = if (ui.tool.destructive) BfColors.Danger else BfColors.Forge),
        ) {
            Text(
                when (ui.tool) {
                    MediaTool.FORMAT -> "Formatar (apaga todo o pendrive)"
                    MediaTool.SURFACE -> "Iniciar teste (apaga todo o pendrive)"
                    MediaTool.ERASE -> "Apagar o pendrive"
                    MediaTool.BACKUP -> "Escolher onde salvar e copiar"
                    MediaTool.RESTORE -> "Restaurar (apaga todo o pendrive)"
                    MediaTool.CHECKSUM -> "Calcular"
                },
            )
        }
        Text(
            "Operações longas continuam com a tela desligada (notificação de progresso). Não remova o pendrive até a mensagem de conclusão.",
            style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim,
        )
    }
}

@Composable
private fun ToolCard(t: MediaTool, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth()
            .border(1.dp, if (selected) BfColors.Forge else BfColors.Outline, shape)
            .selectable(selected, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(t.icon, null, tint = if (selected) BfColors.Forge else BfColors.TextDim, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (t.destructive) Badge("Apaga", BfColors.Danger) else Badge("Só lê", BfColors.Success)
            }
            Text(t.summary, style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
        }
    }
}

@Composable
private fun Choice(selected: Boolean, title: String, summary: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, onClick = onClick), verticalAlignment = Alignment.Top) {
        RadioButton(selected, onClick)
        Column(Modifier.padding(top = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
        }
    }
}

@Composable
private fun Options(ui: ToolsUi, target: DriveReport?, actions: ToolsActions) {
    when (ui.tool) {
        MediaTool.FORMAT -> {
            Text("Sistema de arquivos", style = MaterialTheme.typography.bodyMedium)
            MediaFs.entries.forEach { fs -> Choice(ui.fs == fs, fs.title, fs.summary) { actions.onFs(fs) } }
            Text("Tabela de partições", style = MaterialTheme.typography.bodyMedium)
            Choice(!ui.gpt, "MBR", "A mais compatível (TVs, carros, PCs antigos). Recomendado.") { actions.onGpt(false) }
            Choice(ui.gpt, "GPT", "Padrão moderno; necessário só acima de 2 TB.") { actions.onGpt(true) }
            OutlinedTextField(ui.label, actions.onLabel, label = { Text("Nome do pendrive (até 11 caracteres)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        MediaTool.SURFACE -> {
            Text("Grava uma marca única em cada bloco de 1 MB do pendrive inteiro e depois relê tudo, conferindo byte a byte.", style = MaterialTheme.typography.bodySmall)
            Text("• Encontra setores com defeito\n• Mede a capacidade REAL (detecta pendrive falsificado)\n• Mostra a velocidade de gravação e leitura em cada região", style = MaterialTheme.typography.bodySmall, color = BfColors.Text)
            Notice("APAGA todo o conteúdo. Leva o tempo de encher e reler o pendrive (ex.: 32 GB ≈ 1 a 3 horas). Para conferir só a capacidade SEM apagar, use Pendrives → Teste de capacidade.")
        }
        MediaTool.ERASE -> {
            EraseMode.entries.forEach { m -> Choice(ui.erase == m, m.title, m.summary) { actions.onErase(m) } }
            Notice(
                "Limite honesto: pendrives e cartões guardam áreas reservadas internas que nenhum aparelho alcança. " +
                    "O apagamento completo impede a recuperação por programas comuns; para dados muito sensíveis, destrua a memória fisicamente.",
                BfColors.Steel,
            )
        }
        MediaTool.BACKUP -> {
            Text("Copia o pendrive ou cartão inteiro (todas as partições, inclusive Ventoy e áreas de boot) para um arquivo .img.gz no celular.", style = MaterialTheme.typography.bodySmall)
            Text("Áreas vazias compactam muito; o arquivo final costuma ser bem menor que o pendrive. Ao final, o app mostra o SHA-256 dos dados.", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
            if (target != null) InfoRow("Dados a copiar", Format.bytes(target.capacityBytes))
            Notice("Confira se há espaço livre no celular. Se faltar espaço ou a cópia for cancelada, o arquivo incompleto é apagado.", BfColors.Steel)
        }
        MediaTool.RESTORE -> {
            ui.restoreFile?.let { InfoRow("Imagem", it.name); it.size?.let { s -> InfoRow("Tamanho do arquivo", Format.bytes(s)) } }
                ?: Text("Escolha um arquivo .img ou .img.gz (backup feito pelo BootForge, Rufus, balenaEtcher, dd…).", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
            OutlinedButton(onClick = actions.onPickRestore, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text(if (ui.restoreFile == null) "Escolher imagem" else "Escolher outra imagem")
            }
            Text("Antes de gravar, o app mede o tamanho real da imagem e recusa se ela não couber. No fim, relê o pendrive e confere o SHA-256.", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
        }
        MediaTool.CHECKSUM -> {
            Text("Origem", style = MaterialTheme.typography.bodyMedium)
            when (val s = ui.checksumSource) {
                is ChecksumSource.Phone -> InfoRow("Arquivo do celular", s.file.name + (s.file.size?.let { " (${Format.bytes(it)})" } ?: ""))
                is ChecksumSource.Drive -> InfoRow("No pendrive", "${s.path} (${Format.bytes(s.size)})")
                null -> Text("Escolha um arquivo do celular ou uma imagem do pendrive Ventoy selecionado abaixo.", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
            }
            OutlinedButton(onClick = actions.onPickChecksumFile, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Arquivo do celular")
            }
            val images = target?.takeIf { it.ventoyDataPartition() != null }?.images.orEmpty()
            if (images.isNotEmpty()) {
                Text("Imagens no pendrive Ventoy", style = MaterialTheme.typography.bodyMedium)
                images.forEach { img ->
                    val sel = (ui.checksumSource as? ChecksumSource.Drive)?.path == img.path
                    Choice(sel, img.path.substringAfterLast('/'), "${img.path} — ${Format.bytes(img.sizeBytes)}") { actions.onPickChecksumDrive(img.path, img.sizeBytes) }
                }
            }
            OutlinedTextField(
                ui.expected, actions.onExpected, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("Valor oficial para comparar (opcional)") },
                supportingText = { Text(Checksums.guess(ui.expected)?.let { "Parece ${it.label}" } ?: "Cole o MD5, SHA-1, SHA-256 ou SHA-512 do site oficial") },
            )
        }
    }
}

@Composable
private fun ChecksumResult(source: String, values: Map<HashAlgorithm, String>, expected: String) {
    val match = expected.takeIf { it.isNotBlank() }?.let { Checksums.match(it, values) }
    SectionCard(title = "Resultado", icon = Icons.Filled.Fingerprint, accent = BfColors.Steel) {
        InfoRow("Arquivo", source)
        if (expected.isNotBlank()) {
            if (match != null) Notice("${match.label} confere com o valor informado: arquivo íntegro.", BfColors.Success)
            else Notice("O valor informado NÃO confere com nenhum dos checksums abaixo.", BfColors.Danger)
        }
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                values.forEach { (alg, v) ->
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(alg.label, style = MaterialTheme.typography.labelMedium, color = BfColors.TextDim)
                            if (alg == match) { Spacer(Modifier.width(6.dp)); Badge("confere", BfColors.Success) }
                            if (!alg.secure) { Spacer(Modifier.width(6.dp)); Badge("só p/ conferir cópia", BfColors.TextDim) }
                        }
                        Text(v, style = MonoStyle, color = if (alg == match) BfColors.Success else BfColors.Text)
                    }
                }
            }
        }
        Text("Toque e segure para copiar. MD5 e SHA-1 servem para detectar arquivo corrompido, não para provar autenticidade.", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
    }
}

/** Resultado do teste de superfície com o mapa de velocidade por região. */
@Composable
fun SurfaceResult(r: SurfaceTest.Result) {
    SectionCard(title = "Resultado do teste", icon = Icons.Filled.GridOn, accent = if (r.healthy) BfColors.Success else BfColors.Danger) {
        when {
            r.fake -> Notice("Capacidade FALSA: só ${Format.bytes(r.realBytes)} de ${Format.bytes(r.declaredBytes)} guardam dados.", BfColors.Danger)
            r.badBytes > 0 -> Notice("${Format.bytes(r.badBytes)} com defeito em ${r.badRanges.size} trecho(s).", BfColors.Danger)
            else -> Notice("Superfície 100% íntegra.", BfColors.Success)
        }
        InfoRow("Capacidade anunciada", Format.bytes(r.declaredBytes))
        InfoRow("Capacidade real", Format.bytes(r.realBytes), valueColor = if (r.fake) BfColors.Danger else BfColors.Success)
        InfoRow("Gravação (média)", Format.speed(r.writeBytesPerSec))
        InfoRow("Leitura (média)", Format.speed(r.readBytesPerSec))
        r.badRanges.take(5).forEach { InfoRow("Trecho ruim", "${Format.bytes(it.startByte)} (+${Format.bytes(it.bytes)})", valueColor = BfColors.Danger) }
        if (r.badRanges.size > 5) Text("… e mais ${r.badRanges.size - 5} trechos (lista completa no log).", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
        Text("Velocidade por região (do início ao fim do pendrive)", style = MaterialTheme.typography.labelMedium, color = BfColors.TextDim)
        SpeedMap(r.regions.map { it.writeBytesPerSec }, BfColors.Forge, "Gravação")
        SpeedMap(r.regions.map { it.readBytesPerSec }, BfColors.Steel, "Leitura")
        Text("Quedas bruscas indicam regiões lentas ou desgastadas; em pendrives falsos a gravação costuma despencar perto do limite real.", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
    }
}

@Composable
private fun SpeedMap(values: List<Double>, color: androidx.compose.ui.graphics.Color, label: String) {
    val max = values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.weight(1f))
            Text("máx. ${Format.speed(max)}", style = MaterialTheme.typography.labelSmall, color = BfColors.TextDim)
        }
        Canvas(Modifier.fillMaxWidth().height(48.dp)) {
            if (values.isEmpty()) return@Canvas
            val w = size.width / values.size
            values.forEachIndexed { i, v ->
                val h = (v / max).toFloat().coerceIn(0.03f, 1f) * size.height
                drawRect(color, Offset(i * w, size.height - h), Size((w - 1f).coerceAtLeast(1f), h))
            }
        }
    }
}
