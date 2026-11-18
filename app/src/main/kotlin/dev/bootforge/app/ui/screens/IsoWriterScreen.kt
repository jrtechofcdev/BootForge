package dev.bootforge.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bootforge.app.data.Format
import dev.bootforge.app.ui.AppViewModel
import dev.bootforge.app.ui.DriveReport
import dev.bootforge.app.ui.InspectState
import dev.bootforge.app.ui.IsoSource
import dev.bootforge.app.ui.IsoWriterUi
import dev.bootforge.app.ui.IsoWriterViewModel
import dev.bootforge.app.ui.components.Badge
import dev.bootforge.app.ui.components.BfTopBar
import dev.bootforge.app.ui.components.ConfirmWriteDialog
import dev.bootforge.app.ui.components.InfoRow
import dev.bootforge.app.ui.components.Notice
import dev.bootforge.app.ui.components.SectionCard
import dev.bootforge.app.ui.theme.BfColors
import dev.bootforge.isowriter.IsoAnalysis
import dev.bootforge.isowriter.MethodOption
import dev.bootforge.isowriter.PartitionScheme
import dev.bootforge.isowriter.WriteMethod
import dev.bootforge.usb.UsbDriveInfo

/** Ações da tela de gravação (a tela em si não guarda estado de negócio). */
class IsoWriterActions(
    val onPickIso: () -> Unit = {},
    val onMethod: (WriteMethod) -> Unit = {},
    val onScheme: (PartitionScheme) -> Unit = {},
    val onVerify: (Boolean) -> Unit = {},
    val onTarget: (String) -> Unit = {},
    val onPermission: (String) -> Unit = {},
    val onWrite: () -> Unit = {},
)

/** Ligação com os ViewModels e com o seletor de arquivos do Android. */
@Composable
fun IsoWriterScreen(vm: AppViewModel, wvm: IsoWriterViewModel, onBack: () -> Unit) {
    val ui by wvm.ui.collectAsStateWithLifecycle()
    val drives by vm.drives.drives.collectAsStateWithLifecycle()
    val inspect by vm.inspect.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(wvm::pickIso) }
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

    Column(Modifier.fillMaxSize()) {
        BfTopBar("Gravar ISO", onBack, "Windows e Linux — análise automática do método")
        IsoWriterContent(
            ui, drives, target, targetState,
            IsoWriterActions(
                onPickIso = { picker.launch(arrayOf("application/x-iso9660-image", "application/octet-stream", "*/*")) },
                onMethod = wvm::selectMethod,
                onScheme = wvm::selectScheme,
                onVerify = wvm::setVerify,
                onTarget = { name -> wvm.selectTarget(name); vm.inspect(name) },
                onPermission = { vm.drives.requestPermission(it) },
                onWrite = { confirm = true },
            ),
        )
    }

    val src = ui.source as? IsoSource.Ready
    if (confirm && target != null && src != null && ui.method != null) {
        ConfirmWriteDialog(
            title = "Gravar a ISO?",
            report = target,
            connectedDrives = drives.size,
            destructive = true,
            message = "\"${src.name}\" será gravada com o método \"${ui.method!!.title}\"" +
                (if (ui.method != WriteMethod.DD) " (${ui.scheme.title} + FAT32)" else "") + ".",
            confirmLabel = "Gravar",
            onConfirm = { confirm = false; wvm.write(target.info, target.capacityBytes) },
            onDismiss = { confirm = false },
        )
    }
}

/** Conteúdo da tela (sem estado: tudo chega por parâmetro — usado também nas capturas de teste). */
@Composable
fun IsoWriterContent(
    ui: IsoWriterUi,
    drives: List<UsbDriveInfo>,
    target: DriveReport?,
    targetState: InspectState?,
    actions: IsoWriterActions,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1) Imagem
        SectionCard(title = "1. Imagem", icon = Icons.Filled.Album) {
            when (val s = ui.source) {
                IsoSource.None -> Text("Escolha um arquivo .iso salvo no celular. Ele é analisado sem ser copiado.", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
                is IsoSource.Analyzing -> Loading("Analisando ${s.name}…")
                is IsoSource.Failed -> Notice("Não foi possível analisar ${s.name}: ${s.message}", BfColors.Danger)
                is IsoSource.Ready -> ImageSummary(s.name, s.analysis)
            }
            OutlinedButton(onClick = actions.onPickIso, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.FolderOpen, null)
                Spacer(Modifier.width(8.dp))
                Text(if (ui.source is IsoSource.Ready) "Escolher outra imagem" else "Escolher imagem")
            }
        }

        val analysis = (ui.source as? IsoSource.Ready)?.analysis ?: return@Column

        // 2) Método
        SectionCard(title = "2. Método de gravação", icon = Icons.Filled.Tune) {
            Text("Recomendado: ${analysis.recommended?.title ?: "nenhum"} — ${analysis.recommendationReason}.", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
            analysis.methods.forEach { opt ->
                MethodCard(opt, selected = ui.method == opt.method, recommended = analysis.recommended == opt.method) { actions.onMethod(opt.method) }
            }
        }

        // 3) Opções
        if (ui.method != null) {
            SectionCard(title = "3. Opções", icon = Icons.Filled.Verified) {
                if (ui.method != WriteMethod.DD) {
                    Text("Tabela de partições", style = MaterialTheme.typography.bodyMedium)
                    PartitionScheme.entries.forEach { sc ->
                        Row(Modifier.fillMaxWidth().selectable(ui.scheme == sc, onClick = { actions.onScheme(sc) }), verticalAlignment = Alignment.Top) {
                            RadioButton(ui.scheme == sc, { actions.onScheme(sc) })
                            Column(Modifier.padding(top = 10.dp)) {
                                Text(sc.title + if (sc == analysis.recommendedScheme) "  (recomendado)" else "", style = MaterialTheme.typography.titleSmall)
                                Text(sc.summary, style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Verificar depois de gravar", style = MaterialTheme.typography.bodyMedium)
                        Text("Relê o pendrive e confere o SHA-256 de tudo que foi gravado (leva quase o mesmo tempo da gravação).", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
                    }
                    Switch(ui.verify, actions.onVerify)
                }
            }
        }

        // 4) Pendrive
        val option = ui.method?.let { analysis.option(it) }
        SectionCard(title = "4. Pendrive de destino", icon = Icons.Filled.Usb) {
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
                if (target.blockSize != 512) Notice("Setor de ${target.blockSize} bytes não suportado.", BfColors.Danger)
                if (option != null && option.requiredBytes > target.capacityBytes) {
                    Notice("Pendrive pequeno demais: são necessários ${Format.bytes(option.requiredBytes)}.", BfColors.Danger)
                }
            }
        }

        val canWrite = option != null && option.available && target != null && target.blockSize == 512 && option.requiredBytes <= target.capacityBytes
        Button(
            onClick = actions.onWrite,
            enabled = canWrite,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = BfColors.Danger),
        ) { Text("Gravar (apaga todo o pendrive)") }
        Text(
            "A gravação continua com a tela desligada (notificação de progresso). Não remova o pendrive até a mensagem de conclusão.",
            style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim,
        )
    }
}

@Composable
private fun ImageSummary(name: String, a: IsoAnalysis) {
    val i = a.info
    InfoRow("Arquivo", name)
    InfoRow("Tamanho", Format.bytes(i.size))
    InfoRow("Formato", i.format.label + (a.treeFormat?.let { " ($it)" } ?: ""))
    InfoRow("Rótulo", i.volumeId)
    InfoRow("Sistema", i.os?.name ?: "não identificado")
    InfoRow("Inicialização", listOfNotNull("BIOS".takeIf { i.bootBios }, "UEFI".takeIf { i.bootUefi || a.efiLoaders.isNotEmpty() }).ifEmpty { listOf("não detectada") }.joinToString(" + "))
    InfoRow("ISO híbrida", if (i.hybridMbr || i.hybridGpt) "sim (${listOfNotNull("MBR".takeIf { i.hybridMbr }, "GPT".takeIf { i.hybridGpt }).joinToString("+")})" else "não")
    a.windowsImage?.let { InfoRow(it.path.substringAfterLast('/'), Format.bytes(it.size) + if (it.size > 0xFFFFFFFFL) " (> 4 GB)" else "") }
    if (a.fileCount > 0) InfoRow("Arquivos", "${a.fileCount} (${Format.bytes(a.totalFileBytes)})")
    i.problems.forEach { Notice(it) }
    if (i.isTruncated) Notice("A imagem está incompleta: baixe novamente.", BfColors.Danger)
}

@Composable
private fun MethodCard(opt: MethodOption, selected: Boolean, recommended: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, if (selected) BfColors.Forge else BfColors.Outline, shape)
            .selectable(selected, enabled = opt.available, onClick = onClick)
            .padding(12.dp)
            .alpha(if (opt.available) 1f else 0.6f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected, onClick, enabled = opt.available)
            Text(opt.method.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (recommended) Badge("Recomendado", BfColors.Success)
            else if (!opt.available) Badge("Indisponível", BfColors.TextDim)
        }
        Text(opt.method.summary, style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
        opt.blockers.forEach { Text("✕ $it", style = MaterialTheme.typography.bodySmall, color = BfColors.Danger) }
        if (opt.available) opt.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = BfColors.Text) }
        if (opt.available && opt.requiredBytes > 0) Text("Espaço necessário: ${Format.bytes(opt.requiredBytes)}", style = MaterialTheme.typography.labelSmall, color = BfColors.Steel)
    }
}
