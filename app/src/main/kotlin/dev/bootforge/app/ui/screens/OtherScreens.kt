package dev.bootforge.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bootforge.app.data.Format
import dev.bootforge.app.ui.AppViewModel
import dev.bootforge.app.ui.components.BfTopBar
import dev.bootforge.app.ui.components.InfoRow
import dev.bootforge.app.ui.components.Notice
import dev.bootforge.app.ui.components.SectionCard
import dev.bootforge.app.ui.theme.BfColors

/** Histórico de operações reais realizadas neste aparelho. */
@Composable
fun HistoryScreen(vm: AppViewModel, onBack: () -> Unit) {
    val entries by vm.history.entries.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        BfTopBar("Histórico", onBack, "${entries.size} operações registradas")
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (entries.isEmpty()) item { Notice("Nenhuma operação registrada ainda.", BfColors.Steel, Icons.Filled.Info) }
            items(entries) { e ->
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (e.success) Icons.Filled.CheckCircle else Icons.Filled.Error, null, tint = if (e.success) BfColors.Success else BfColors.Danger)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.operation, style = MaterialTheme.typography.titleMedium)
                            Text("${Format.dateTime(e.timestamp)} • ${e.device}", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
                        }
                    }
                    Text(e.details, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (entries.isNotEmpty()) item {
                OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) { Text("Limpar histórico") }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Limpar histórico?") },
            text = { Text("Os registros serão apagados deste aparelho. Os pendrives não são afetados.") },
            confirmButton = { Button(onClick = { vm.history.clear(); confirmClear = false }) { Text("Limpar") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } },
            containerColor = BfColors.Surface,
        )
    }
}

/** Sobre, integridade do pacote embutido, licenças e limitações conhecidas. */
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit, onLogs: () -> Unit = {}) {
    val check by vm.packageCheck.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val appVersion = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
    }
    Column(Modifier.fillMaxSize()) {
        BfTopBar("Configurações", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = "Sobre o BootForge", icon = Icons.Filled.Info) {
                InfoRow("Versão do app", appVersion)
                InfoRow("Ventoy embutido", runCatching { vm.ventoyPackage.version }.getOrNull())
                InfoRow("Descrição", "Universal Bootable Media Manager")
                Text("Processamento 100% no celular via USB OTG, sem root e sem computador.", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
            }
            SectionCard(title = "Integridade do pacote Ventoy", icon = Icons.Filled.VerifiedUser) {
                Text("Confere o SHA-256 dos componentes originais do Ventoy embutidos no app.", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
                Button(onClick = vm::checkEmbeddedPackage, modifier = Modifier.fillMaxWidth()) { Text("Verificar agora") }
                check?.let { Notice(it, if (it.startsWith("PROBLEMA")) BfColors.Danger else BfColors.Success) }
            }
            SectionCard(title = "Logs e diagnóstico", icon = Icons.Filled.Info) {
                Text("Se algo der errado, gere o relatório de diagnóstico e envie para análise.", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
                Button(onClick = onLogs, modifier = Modifier.fillMaxWidth()) { Text("Abrir logs e diagnóstico") }
            }
            SectionCard(title = "Licenças", icon = Icons.Filled.Gavel) {
                License("BootForge", "GNU GPL v3")
                License("Ventoy 1.1.18 (boot.img, core.img, VTOYEFI)", "GNU GPL v3 — componentes internos com suas licenças (GRUB2: GPLv3; shim/EDK2: BSD; etc.)")
                License("libaums (USB Mass Storage)", "Apache 2.0")
                License("XZ for Java", "0BSD / domínio público")
                License("Kotlin, AndroidX, Jetpack Compose, kotlinx.serialization", "Apache 2.0")
                License("Bouncy Castle (assinaturas OpenPGP)", "MIT")
                License("Tabela up-case exFAT", "Especificação exFAT da Microsoft (publicação aberta)")
                Text("Textos completos em THIRD_PARTY_LICENSES.md no código-fonte.", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
            }
            SectionCard(title = "Limitações conhecidas desta versão", icon = Icons.Filled.ReportProblem, accent = BfColors.Warning) {
                listOf(
                    "Pendrives até 1 TiB (limite do driver SCSI atual) e apenas a primeira unidade lógica (LUN 0).",
                    "Gerenciador de arquivos e de ISOs somente para partição de dados exFAT (padrão do Ventoy); NTFS/FAT32 aparecem sem estatísticas.",
                    "Persistência em ext2/ext3 (o script oficial também aceita ext4/xfs).",
                    "Pré-visualização de temas é aproximada.",
                    "Gravação inteligente de ISO (Etapa 3), editor de ISO (Etapa 5) e catálogo de sistemas (Etapa 6): próximas etapas.",
                ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun License(name: String, license: String) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Text(name, style = MaterialTheme.typography.bodyMedium)
        Text(license, style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
    }
}
