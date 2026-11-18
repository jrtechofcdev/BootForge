package dev.bootforge.app.ui.screens.ventoy

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bootforge.app.data.Format
import dev.bootforge.app.ui.HubState
import dev.bootforge.app.ui.VentoyViewModel
import dev.bootforge.app.ui.components.Notice
import dev.bootforge.app.ui.components.SectionCard
import dev.bootforge.app.ui.theme.BfColors
import dev.bootforge.app.ui.theme.MonoStyle
import dev.bootforge.fs.ext.ExtFormatter
import dev.bootforge.ventoy.CheckItem
import dev.bootforge.ventoy.VentoyPersistence

/** Ferramentas originais do Ventoy adaptadas: verificação, Secure Boot, backup e persistência. */
@Composable
fun ToolsTab(vm: VentoyViewModel, hub: HubState) {
    val report by vm.check.collectAsStateWithLifecycle()
    val isos by vm.isos.collectAsStateWithLifecycle()
    var confirmSb by remember { mutableStateOf(false) }
    var includeLarge by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var restoreList by remember { mutableStateOf<List<Pair<String, Long>>?>(null) }
    var persist by remember { mutableStateOf(false) }
    val sbOn = hub.ventoy.secureBootSupport != false

    val createZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let { vm.backupTo(it, includeLarge) } }
    val openZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { restoreUri = uri; vm.previewBackup(uri) { restoreList = it } }
    }
    LaunchedEffect(Unit) { if (isos.entries.isEmpty()) vm.loadCatalog() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(title = "Verificar instalação", icon = Icons.Filled.FactCheck) {
            Text("Confere partições, assinatura do MBR (a mesma checagem que o Ventoy faz ao iniciar), carregador GRUB, a VTOYEFI " +
                "byte a byte com o pacote oficial, o sistema de arquivos exFAT e o ventoy.json. Nada é alterado.",
                style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
            Button(onClick = vm::runCheck, modifier = Modifier.fillMaxWidth()) { Text("Verificar agora") }
            report?.forEach { item ->
                val (sym, color) = when (item.level) {
                    CheckItem.Level.OK -> "✓" to BfColors.Success
                    CheckItem.Level.WARNING -> "!" to BfColors.Warning
                    CheckItem.Level.ERROR -> "✗" to BfColors.Danger
                    CheckItem.Level.INFO -> "i" to BfColors.Steel
                }
                Column(Modifier.padding(vertical = 2.dp)) {
                    Text("$sym ${item.title}", color = color, style = MaterialTheme.typography.bodyMedium)
                    if (item.detail.isNotBlank()) Text(item.detail, style = MonoStyle, color = BfColors.TextDim)
                }
            }
        }

        SectionCard(title = "Secure Boot", icon = Icons.Filled.Security) {
            Text("Situação atual: ${if (sbOn) "LIGADO (shim + MokManager)" else "DESLIGADO (GRUB direto)"}", style = MaterialTheme.typography.bodyMedium)
            Text("Mesmo procedimento do Ventoy2Disk/vtoycli: só a partição VTOYEFI é regravada. Suas ISOs e arquivos não são tocados. " +
                "Se a versão do pendrive for diferente da do app, ela também é atualizada.", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
            Button(onClick = { confirmSb = true }, modifier = Modifier.fillMaxWidth()) { Text(if (sbOn) "Desligar Secure Boot" else "Ligar Secure Boot") }
        }

        SectionCard(title = "Backup da configuração", icon = Icons.Filled.Backup) {
            Text("Salva a pasta /ventoy (ventoy.json, temas, scripts) em um .zip no celular.", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Incluir arquivos grandes (> ${Format.bytes(VentoyMaintenanceLarge)}, ex.: .dat)", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Switch(includeLarge, { includeLarge = it })
            }
            Button(onClick = { createZip.launch("ventoy-backup.zip") }, modifier = Modifier.fillMaxWidth()) { Text("Salvar backup (.zip)") }
            OutlinedButton(onClick = { openZip.launch(arrayOf("application/zip", "*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Restaurar backup…") }
        }

        SectionCard(title = "Persistência (Linux live)", icon = Icons.Filled.Save) {
            Text("Cria um arquivo .dat (ext2/ext3) para a distro guardar alterações entre boots — como o CreatePersistentImg.sh oficial.",
                style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
            Button(onClick = { persist = true }, modifier = Modifier.fillMaxWidth()) { Text("Criar arquivo de persistência") }
        }
    }

    if (confirmSb) ConfirmDialog(if (sbOn) "Desligar Secure Boot?" else "Ligar Secure Boot?",
        "A partição VTOYEFI (32 MiB) será regravada a partir do pacote ${runCatching { vm.embedded.version }.getOrDefault("")} do app. Os dados do pendrive são preservados.",
        "Confirmar", onConfirm = { vm.setSecureBoot(!sbOn); confirmSb = false }, onDismiss = { confirmSb = false })

    restoreList?.let { list ->
        var overwrite by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { restoreList = null },
            title = { Text("Restaurar ${list.size} arquivo(s)?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(overwrite, { overwrite = it }); Text(" Substituir arquivos existentes") }
                    list.take(60).forEach { (n, s) -> Text("$n (${Format.bytes(s)})", style = MonoStyle, color = BfColors.TextDim) }
                    if (list.size > 60) Text("… e mais ${list.size - 60}")
                }
            },
            confirmButton = { Button(onClick = { restoreUri?.let { vm.restoreFrom(it, overwrite) }; restoreList = null }) { Text("Restaurar") } },
            dismissButton = { TextButton(onClick = { restoreList = null }) { Text("Cancelar") } },
            containerColor = BfColors.Surface,
        )
    }

    if (persist) PersistenceDialog(isos.entries.filter { it.visible && it.type == "iso" }.map { it.path }, onCreate = { vm.createPersistence(it); persist = false }, onDismiss = { persist = false })
}

private const val VentoyMaintenanceLarge = 64L shl 20

@Composable
private fun PersistenceDialog(images: List<String>, onCreate: (VentoyPersistence.Request) -> Unit, onDismiss: () -> Unit) {
    var preset by remember { mutableStateOf(VentoyPersistence.Preset.UBUNTU) }
    var sizeText by remember { mutableStateOf("4096") }
    var ext3 by remember { mutableStateOf(true) }
    var zero by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf(images.firstOrNull()) }
    var path by remember(preset) { mutableStateOf("/persistence/${if (preset == VentoyPersistence.Preset.UBUNTU) "ubuntu" else "debian"}.dat") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Criar persistência") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                VentoyPersistence.Preset.entries.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(preset == p, { preset = p }); Text(p.title, style = MaterialTheme.typography.bodySmall) }
                }
                Text("Rótulo: ${preset.label}" + (preset.configFile?.let { " • cria ${it.first} (\"${it.second.trim()}\")" } ?: ""), style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
                OutlinedTextField(sizeText, { sizeText = it.filter(Char::isDigit).take(6) }, label = { Text("Tamanho (MiB)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(path, { path = it }, label = { Text("Arquivo no pendrive") }, singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = MonoStyle)
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(ext3, { ext3 = it }); Text(if (ext3) " ext3 (com journal, recomendado)" else " ext2 (sem journal)") }
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(zero, { zero = it }); Text(" Zerar o arquivo (mais lento)") }
                Text("Vincular à ISO (plugin persistence):", style = MaterialTheme.typography.bodySmall)
                (listOf<String?>(null) + images).forEach { img ->
                    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(link == img, { link = img }); Text(img ?: "(não vincular agora)", style = MonoStyle) }
                }
                Notice("ext4, xfs e criptografia (opções do script oficial) ainda não estão disponíveis no app.", BfColors.Steel)
            }
        },
        confirmButton = {
            Button(onClick = {
                onCreate(VentoyPersistence.Request(path.trim(), sizeText.toLongOrNull() ?: 0, if (ext3) ExtFormatter.Type.EXT3 else ExtFormatter.Type.EXT2,
                    preset.label, preset.configFile, zero, link))
            }, enabled = (sizeText.toLongOrNull() ?: 0) >= 4 && path.startsWith("/")) { Text("Criar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        containerColor = BfColors.Surface,
    )
}
