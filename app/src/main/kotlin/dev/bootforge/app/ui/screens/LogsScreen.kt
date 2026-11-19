package dev.bootforge.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.bootforge.app.data.AppLog
import dev.bootforge.app.data.Format
import dev.bootforge.app.data.LogExport
import dev.bootforge.app.data.LogLine
import dev.bootforge.app.ui.components.BfTopBar
import dev.bootforge.app.ui.components.Notice
import dev.bootforge.app.ui.components.SectionCard
import dev.bootforge.app.ui.screens.ventoy.ConfirmDialog
import dev.bootforge.app.ui.theme.BfColors
import dev.bootforge.app.ui.theme.MonoStyle
import dev.bootforge.integrity.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogsActions(
    val onSave: () -> Unit = {}, val onShare: () -> Unit = {}, val onCopy: () -> Unit = {},
    val onClear: () -> Unit = {}, val onVerbose: (Boolean) -> Unit = {}, val onFilter: (Diag.Level?) -> Unit = {}, val onRefresh: () -> Unit = {},
    val onTransferKb: (Int) -> Unit = {},
)

/** Tela "Logs e diagnóstico": ver, salvar, compartilhar e limpar o log (Etapa 7). */
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as android.app.Application
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf<Diag.Level?>(null) }
    var verbose by remember { mutableStateOf(Diag.verbose) }
    var busy by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var transferKb by remember { mutableIntStateOf(dev.bootforge.app.data.AppGraph.usbTransferKb) }
    val lines = remember(refresh, filter) { AppLog.recent().filter { filter == null || it.level >= filter!! }.takeLast(500).reversed() }

    val saveTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        if (uri != null) scope.launch {
            busy = "Salvando…"
            busy = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(LogExport.build(ctx).toByteArray()) }; "Relatório salvo. Envie esse arquivo para análise." }
                    .getOrElse { "Não foi possível salvar: ${it.message}" }
            }
        }
    }

    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
        BfTopBar("Logs e diagnóstico", onBack, "Para enviar quando algo der errado")
        LogsContent(
            lines, AppLog.totalBytes(), AppLog.allCrashReports().size, verbose, filter, busy, transferKb,
            LogsActions(
                onSave = { saveTo.launch("bootforge-log-${java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())}.txt") },
                onShare = {
                    scope.launch {
                        busy = "Preparando o relatório…"
                        val f = withContext(Dispatchers.IO) { runCatching { LogExport.writeFile(ctx) }.getOrNull() }
                        busy = null
                        if (f != null) runCatching { ctx.startActivity(LogExport.shareIntent(ctx, f)) } else busy = "Não foi possível gerar o relatório"
                    }
                },
                onCopy = {
                    scope.launch {
                        val text = withContext(Dispatchers.IO) { LogExport.build(ctx) }
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        // a área de transferência tem limite: copia o final (parte mais recente)
                        cm.setPrimaryClip(ClipData.newPlainText("BootForge log", text.takeLast(200_000)))
                        busy = "Copiado (últimos ${Format.bytes(minOf(text.length, 200_000).toLong())}). Para o relatório completo, use Salvar ou Compartilhar."
                    }
                },
                onClear = { confirmClear = true },
                onVerbose = { verbose = it; AppLog.setVerbose(app, it) },
                onFilter = { filter = it },
                onRefresh = { refresh++ },
                onTransferKb = { transferKb = it; dev.bootforge.app.data.AppGraph.usbTransferKb = it },
            ),
        )
    }
    if (confirmClear) ConfirmDialog("Apagar os logs?", "Os registros e relatórios de travamento guardados no aparelho serão apagados.", "Apagar", true, { AppLog.clear(); confirmClear = false; refresh++ }, { confirmClear = false })
}

@Composable
fun LogsContent(lines: List<LogLine>, totalBytes: Long, crashes: Int, verbose: Boolean, filter: Diag.Level?, busy: String?, transferKb: Int, actions: LogsActions) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionCard(title = "Enviar relatório", icon = Icons.Filled.BugReport) {
            Text(
                "Se algo der errado, gere o relatório e envie para análise. Ele traz o aparelho, a versão do app, os pendrives conectados, " +
                    "o histórico, os erros com detalhes técnicos e o log do sistema deste app.",
                style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim,
            )
            dev.bootforge.app.ui.components.BoldLead("Privacidade:", "não inclui conteúdo de arquivos nem contas; números de série aparecem mascarados.")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.onShare) { Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Compartilhar") }
                LogAction(Icons.Filled.Save, "Salvar .txt", actions.onSave)
                LogAction(Icons.Filled.ContentCopy, "Copiar", actions.onCopy)
                LogAction(Icons.Filled.Delete, "Limpar", actions.onClear)
            }
            busy?.let { Notice(it, BfColors.Steel) }
            Text("Guardado: ${Format.bytes(totalBytes)} · travamentos registrados: $crashes", style = MaterialTheme.typography.labelSmall, color = BfColors.TextDim)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Log detalhado", style = MaterialTheme.typography.bodyMedium)
                    Text("Registra cada comando enviado ao pendrive. Ligue só para reproduzir um erro (deixa a gravação mais lenta).", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
                }
                Switch(verbose, actions.onVerbose)
            }
        }
        SectionCard(title = "Compatibilidade USB", icon = Icons.Filled.Usb) {
            Text(
                "Tamanho de cada comando enviado ao pendrive. O padrão (64 KB) funciona na maioria; se um pendrive falhar ou travar, " +
                    "o app já reduz sozinho — escolha um valor menor aqui só se o erro continuar.",
                style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(64 to "64 KB (padrão)", 32 to "32 KB", 16 to "16 KB", 4 to "4 KB (mais lento)", 128 to "128 KB").forEach { (kb, label) ->
                    FilterChip(transferKb == kb, { actions.onTransferKb(kb) }, label = { Text(label) })
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(filter == null, { actions.onFilter(null) }, label = { Text("Tudo") })
            FilterChip(filter == Diag.Level.WARN, { actions.onFilter(Diag.Level.WARN) }, label = { Text("Avisos e erros") })
            FilterChip(filter == Diag.Level.ERROR, { actions.onFilter(Diag.Level.ERROR) }, label = { Text("Só erros") })
            OutlinedButton(onClick = actions.onRefresh) { Text("Atualizar") }
        }
        if (lines.isEmpty()) Text("Nenhum registro.", color = BfColors.TextDim)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(lines) { l ->
                val color = when (l.level) { Diag.Level.ERROR -> BfColors.Danger; Diag.Level.WARN -> BfColors.Warning; Diag.Level.INFO -> BfColors.Text; Diag.Level.DEBUG -> BfColors.TextDim }
                Text(
                    l.format(), style = MonoStyle, color = color, maxLines = 12,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(BfColors.Surface).padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun LogAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(label) }
}
