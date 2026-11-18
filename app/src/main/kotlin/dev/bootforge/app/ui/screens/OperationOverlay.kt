package dev.bootforge.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import dev.bootforge.app.data.Format
import dev.bootforge.app.ui.AppViewModel
import dev.bootforge.app.data.OperationState
import dev.bootforge.app.ui.components.Notice
import dev.bootforge.app.ui.theme.BfColors
import dev.bootforge.app.ui.theme.MonoStyle

/**
 * Painel de progresso de operações longas. Mostra SOMENTE dados reais:
 * etapa atual, porcentagem, bytes processados, velocidade e tempo estimado.
 */
@Composable
fun OperationOverlay(vm: AppViewModel, op: OperationState) {
    val running = op.status == OperationState.Status.RUNNING
    // Mantém a tela ligada durante a operação (evita o Android pausar o app no meio da gravação).
    val view = LocalView.current
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }
    BackHandler(enabled = true) { if (!running) vm.dismissOperation() }
    OperationContent(op, onCancel = vm::cancelOperation, onClose = vm::dismissOperation)
}

/** Parte visual do painel (sem estado) — também usada nas capturas de tela de teste. */
@Composable
fun OperationContent(op: OperationState, onCancel: () -> Unit, onClose: () -> Unit) {
    val running = op.status == OperationState.Status.RUNNING
    Box(Modifier.fillMaxSize().background(BfColors.Background.copy(alpha = 0.92f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .padding(20.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(BfColors.Surface)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(op.title, style = MaterialTheme.typography.titleLarge)
            Text(op.deviceLabel, style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)

            when (op.status) {
                OperationState.Status.RUNNING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = BfColors.Forge)
                        Spacer(Modifier.width(10.dp))
                        val stepText = if (op.stepCount > 0) "Etapa ${op.stepIndex + 1} de ${op.stepCount}: ${op.step}" else op.step
                        Text(stepText, style = MaterialTheme.typography.bodyMedium)
                    }
                    val p = op.progress
                    if (p != null && p.bytesTotal > 0) {
                        LinearProgressIndicator(
                            progress = { p.fraction },
                            modifier = Modifier.fillMaxWidth(),
                            color = BfColors.Forge,
                            trackColor = BfColors.SurfaceHigh,
                        )
                        Text(
                            "${(p.fraction * 100).toInt()}%  •  ${Format.bytes(p.bytesDone)} de ${Format.bytes(p.bytesTotal)}  •  ${Format.speed(p.bytesPerSecond)}  •  restante: ${Format.eta(p.etaSeconds)}",
                            style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim,
                        )
                    }
                    Notice("Não desconecte o pendrive e mantenha o app aberto até o fim.", BfColors.Warning, Icons.Filled.Info)
                }
                OperationState.Status.SUCCESS -> Notice(op.result ?: "Concluído", BfColors.Success, Icons.Filled.CheckCircle)
                OperationState.Status.FAILED -> Notice("A operação FALHOU. ${op.result ?: ""}", BfColors.Danger, Icons.Filled.Error)
                OperationState.Status.CANCELLED -> Notice(op.result ?: "Cancelado", BfColors.Warning, Icons.Filled.Info)
            }

            if (op.logs.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(BfColors.Background)
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                ) {
                    op.logs.forEach { Text(it, style = MonoStyle, color = BfColors.TextDim) }
                }
            }

            if (running) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
            } else {
                Button(
                    onClick = onClose,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = BfColors.Forge),
                ) { Text("Fechar") }
            }
        }
    }
}
