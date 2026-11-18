package dev.bootforge.app.ui.screens.ventoy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import dev.bootforge.app.ui.ConflictPolicy
import dev.bootforge.app.ui.theme.BfColors
import dev.bootforge.app.ui.theme.MonoStyle

/** Diálogo com um campo de texto (nome de pasta, novo nome, caminho de destino…). */
@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String = "",
    confirmLabel: String = "OK",
    help: String? = null,
    multiline: Boolean = false,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                help?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim) }
                OutlinedTextField(
                    text, { text = it }, label = { Text(label) }, singleLine = !multiline,
                    modifier = Modifier.fillMaxWidth().let { if (multiline) it.heightIn(min = 140.dp) else it },
                    textStyle = if (multiline) MonoStyle else MaterialTheme.typography.bodyLarge,
                )
            }
        },
        confirmButton = { Button(onClick = { onConfirm(text) }, enabled = text.isNotBlank() || multiline) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        containerColor = BfColors.Surface,
    )
}

/** Confirmação simples (para exclusões e ações que gravam no pendrive). */
@Composable
fun ConfirmDialog(title: String, text: String, confirmLabel: String, danger: Boolean = false, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(text) } },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = if (danger) BfColors.Danger else BfColors.Forge)) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        containerColor = BfColors.Surface,
    )
}

/** Escolha do que fazer quando o arquivo já existe no pendrive. */
@Composable
fun ConflictDialog(onChoose: (ConflictPolicy) -> Unit, onDismiss: () -> Unit) {
    var choice by remember { mutableStateOf(ConflictPolicy.KEEP_BOTH) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Se o arquivo já existir…") },
        text = {
            Column {
                ConflictPolicy.entries.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(choice == p, { choice = p })
                        Text(p.label)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onChoose(choice) }) { Text("Copiar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        containerColor = BfColors.Surface,
    )
}
