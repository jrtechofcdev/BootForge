package dev.bootforge.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.bootforge.app.data.Format
import dev.bootforge.app.ui.DriveReport
import dev.bootforge.app.ui.theme.BfColors

/**
 * Confirmação de operação que grava no pendrive.
 *
 * Proteções:
 *  - mostra fabricante, modelo, série, capacidade e partições do dispositivo-alvo;
 *  - operações destrutivas exigem digitar a palavra APAGAR;
 *  - com mais de um pendrive conectado, exige marcar que o dispositivo foi conferido.
 */
@Composable
fun ConfirmWriteDialog(
    title: String,
    report: DriveReport,
    connectedDrives: Int,
    destructive: Boolean,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    var checked by remember { mutableStateOf(false) }
    val multi = connectedDrives > 1
    val ok = (!destructive || typed.trim().equals(KEYWORD, ignoreCase = false)) && (!multi || checked)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Warning, null, tint = if (destructive) BfColors.Danger else BfColors.Warning) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                SectionCard {
                    InfoRow("Dispositivo", report.info.displayName)
                    InfoRow("Capacidade", "${Format.bytes(report.capacityBytes)} (${report.blockCount} setores)")
                    InfoRow("Nº de série", report.info.serial)
                    InfoRow("Tabela atual", report.layout.scheme.label)
                    InfoRow("Partições", report.layout.partitions.joinToString { "#${it.number} ${it.fs?.type?.label ?: "?"} ${Format.bytes(it.sectorCount * report.blockSize)}" }.ifEmpty { "nenhuma" })
                }
                if (destructive) {
                    Text("TODOS os dados deste pendrive serão apagados. Para confirmar, digite $KEYWORD:", color = BfColors.Danger)
                    OutlinedTextField(typed, { typed = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text(KEYWORD) })
                }
                if (multi) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked, { checked = it })
                        Text("Há $connectedDrives pendrives conectados. Conferi que o alvo é \"${report.info.displayName}\" de ${Format.bytes(report.capacityBytes)}.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = ok,
                colors = ButtonDefaults.buttonColors(containerColor = if (destructive) BfColors.Danger else BfColors.Forge),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        containerColor = BfColors.Surface,
    )
}

private const val KEYWORD = "APAGAR"
