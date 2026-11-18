package dev.bootforge.app.ui.screens

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bootforge.app.data.Format
import dev.bootforge.app.ui.AppViewModel
import dev.bootforge.app.ui.DriveReport
import dev.bootforge.app.ui.InspectState
import dev.bootforge.app.ui.components.Badge
import dev.bootforge.app.ui.components.BfTopBar
import dev.bootforge.app.ui.components.CapacityBar
import dev.bootforge.app.ui.components.ConfirmWriteDialog
import dev.bootforge.app.ui.components.InfoRow
import dev.bootforge.app.ui.components.Notice
import dev.bootforge.app.ui.components.SectionCard
import dev.bootforge.app.ui.theme.BfColors
import dev.bootforge.app.ui.theme.MonoStyle
import dev.bootforge.ventoy.PartitionStyle
import dev.bootforge.ventoy.VentoyInstallOptions
import dev.bootforge.ventoy.VentoyLayout

/** Instalação do Ventoy com as opções oficiais disponíveis. */
@Composable
fun VentoyInstallScreen(vm: AppViewModel, id: String, onBack: () -> Unit) {
    val state by vm.inspect.collectAsStateWithLifecycle()
    val drives by vm.drives.drives.collectAsStateWithLifecycle()
    LaunchedEffect(id) {
        val s = vm.inspect.value
        if (!(s is InspectState.Ready && s.report.info.deviceName == id)) vm.inspect(id)
    }
    Column(Modifier.fillMaxSize()) {
        BfTopBar("Instalar Ventoy", onBack, "Ventoy ${runCatching { vm.ventoyPackage.version }.getOrDefault("?")}")
        val s = state
        if (s !is InspectState.Ready || s.report.info.deviceName != id) {
            if (s is InspectState.Failed) Notice(s.message, BfColors.Danger) else Loading("Lendo o pendrive…")
            return@Column
        }
        InstallForm(s.report, drives.size) { opts -> vm.installVentoy(s.report.info, opts, s.report.capacityBytes) }
    }
}

@Composable
fun InstallForm(r: DriveReport, connected: Int, onInstall: (VentoyInstallOptions) -> Unit) {
    var style by remember { mutableStateOf(if (r.blockCount > VentoyLayout.MBR_MAX_SECTORS) PartitionStyle.GPT else PartitionStyle.MBR) }
    var align by remember { mutableStateOf(true) }
    var secureBoot by remember { mutableStateOf(true) }
    var reserveText by remember { mutableStateOf("0") }
    var confirm by remember { mutableStateOf(false) }
    val reserve = reserveText.toLongOrNull()
    val options = VentoyInstallOptions(style = style, reserveMiB = reserve ?: -1, align4k = align, secureBoot = secureBoot)
    val layout = runCatching { VentoyLayout.compute(r.blockCount, options) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(title = "Dispositivo", icon = Icons.Filled.Info) {
            InfoRow("Pendrive", r.info.displayName)
            InfoRow("Capacidade", Format.bytes(r.capacityBytes))
            InfoRow("Setor", "${r.blockSize} bytes")
            if (r.blockSize != 512) Notice("Setor nativo de ${r.blockSize} bytes: o Ventoy não suporta este tipo de dispositivo.", BfColors.Danger)
        }

        SectionCard(title = "Estilo de partição", icon = Icons.Filled.Tune) {
            PartitionStyle.entries.forEach { st ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected = style == st, onClick = { style = st }),
                    verticalAlignment = Alignment.Top,
                ) {
                    RadioButton(selected = style == st, onClick = { style = st })
                    Column(Modifier.padding(top = 10.dp)) {
                        Text(st.label, style = MaterialTheme.typography.titleMedium)
                        Text(st.description, style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
                    }
                }
            }
        }

        SectionCard(title = "Opções", icon = Icons.Filled.Tune) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Alinhar partições em 4 KB", style = MaterialTheme.typography.bodyMedium)
                    Text("Recomendado: melhora o desempenho em memórias flash.", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
                }
                Switch(align, { align = it })
            }
            OutlinedTextField(
                reserveText, { reserveText = it.filter(Char::isDigit).take(7) },
                label = { Text("Espaço reservado no fim do disco (MiB)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Suporte a Secure Boot", style = MaterialTheme.typography.bodyMedium)
                    Text(if (secureBoot) "Ligado (padrão do Ventoy): funciona em PCs com e sem Secure Boot." else "Desligado: mesmo ajuste do Ventoy2Disk oficial (GRUB direto, sem shim).", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
                }
                Switch(secureBoot, { secureBoot = it })
            }
        }

        SectionCard(title = "Resultado previsto", icon = Icons.Filled.Info) {
            layout.onSuccess { l ->
                InfoRow("Partição 1 (exFAT \"Ventoy\")", Format.bytes(l.part1Sectors * 512))
                InfoRow("Partição 2 (VTOYEFI, FAT16)", Format.bytes(l.part2Sectors * 512))
                InfoRow("Setores", "1: ${l.part1Start}–${l.part1End}\n2: ${l.part2Start}–${l.part2End}", mono = true)
            }.onFailure { Notice(it.message ?: "Configuração inválida", BfColors.Danger) }
        }

        Button(
            onClick = { confirm = true },
            enabled = layout.isSuccess && r.blockSize == 512 && reserve != null,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = BfColors.Danger),
        ) { Text("Instalar (apaga todo o pendrive)") }
    }

    if (confirm) {
        ConfirmWriteDialog(
            title = "Instalar o Ventoy?",
            report = r,
            connectedDrives = connected,
            destructive = true,
            message = "O pendrive será particionado (${style.label}), formatado e receberá os componentes originais do Ventoy.",
            confirmLabel = "Instalar",
            onConfirm = { confirm = false; onInstall(options) },
            onDismiss = { confirm = false },
        )
    }
}
