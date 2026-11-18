package dev.bootforge.app.ui.screens.ventoy

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bootforge.app.data.Format
import dev.bootforge.app.ui.VentoyViewModel
import dev.bootforge.app.ui.components.Badge
import dev.bootforge.app.ui.components.InfoRow
import dev.bootforge.app.ui.components.Notice
import dev.bootforge.app.ui.components.SectionCard
import dev.bootforge.app.ui.screens.Loading
import dev.bootforge.app.ui.theme.BfColors
import dev.bootforge.app.ui.theme.MonoStyle
import dev.bootforge.iso.ImageCategory
import dev.bootforge.ventoy.CatalogEntry

/** Gerenciador de ISOs: o que aparece no menu do Ventoy, identificação, integridade e organização. */
@Composable
fun IsosTab(vm: VentoyViewModel) {
    val st by vm.isos.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<CatalogEntry?>(null) }
    var addDest by remember { mutableStateOf<List<Uri>?>(null) }
    LaunchedEffect(Unit) { if (st.entries.isEmpty()) vm.loadCatalog() }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) addDest = it }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { pick.launch(arrayOf("*/*")) }) { Text("Adicionar ISO") }
            OutlinedButton(onClick = vm::proposeOrganization) { Text("Organizar em pastas") }
            OutlinedButton(onClick = vm::loadCatalog) { Text("Atualizar") }
        }
        if (st.loading) Loading("Procurando imagens (regras do Ventoy)…")
        val visible = st.entries.count { it.visible }
        Text("${st.entries.size} imagem(ns) encontrada(s); $visible aparecem no menu do Ventoy.", Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(st.entries, key = { it.path }) { e ->
                val info = st.details[e.path]
                SectionCard(Modifier.clickable { selected = e; if (info == null) vm.inspectImage(e.path) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Badge(e.type.uppercase(), BfColors.Steel)
                        if (e.visible) Badge(if (e.uefiOnly) "No menu (só UEFI)" else "No menu", BfColors.Success) else Badge("Oculta", BfColors.Warning)
                        info?.os?.category?.let { Badge(it.folder) }
                    }
                    Text(info?.os?.name?.let { "$it — ${e.name}" } ?: e.name, style = MaterialTheme.typography.titleMedium)
                    Text("${e.folder} • ${Format.bytes(e.size)}${if (!e.contiguous) " • fragmentada" else ""}", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
                    e.hiddenReason?.let { Text("Motivo: $it", style = MaterialTheme.typography.bodySmall, color = BfColors.Warning) }
                    info?.problems?.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = BfColors.Danger) }
                }
            }
        }
    }

    selected?.let { e -> IsoDetails(vm, e, st.details[e.path]) { selected = null } }
    addDest?.let { uris ->
        AddIsoDialog(uris.size, onConfirm = { dir, sha -> vm.addImages(uris, dir, sha); addDest = null }, onDismiss = { addDest = null })
    }
    st.proposal?.let { p -> OrganizeDialog(p, onApply = vm::applyOrganization, onDismiss = vm::dismissProposal) }
}

@Composable
private fun IsoDetails(vm: VentoyViewModel, e: CatalogEntry, info: dev.bootforge.iso.ImageInfo?, onClose: () -> Unit) {
    var action by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(e.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                InfoRow("Local", e.folder, mono = true)
                InfoRow("Tamanho", Format.bytes(e.size))
                InfoRow("No menu do Ventoy", if (e.visible) "sim" else "não — ${e.hiddenReason}")
                if (info == null) Text("Analisando conteúdo…", color = BfColors.TextDim) else {
                    InfoRow("Sistema", info.os?.name ?: "não identificado")
                    info.os?.let { InfoRow("Identificado por", it.evidence) }
                    InfoRow("Formato", info.format.label)
                    InfoRow("Rótulo", info.volumeId)
                    InfoRow("Boot", listOfNotNull("BIOS".takeIf { info.bootBios }, "UEFI".takeIf { info.bootUefi }).joinToString(" + ").ifEmpty { "não detectado" })
                    InfoRow("Híbrida", listOfNotNull("MBR".takeIf { info.hybridMbr }, "GPT".takeIf { info.hybridGpt }).joinToString(" + ").ifEmpty { "não" })
                    info.diskInfo?.let { InfoRow("/.disk/info", it) }
                    if (info.problems.isEmpty()) Notice("Estrutura sem problemas aparentes.", BfColors.Success)
                    info.problems.forEach { Notice(it, BfColors.Danger) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip({ action = "verify" }, { Text("Verificar integridade") })
                    AssistChip({ action = "rename" }, { Text("Renomear") })
                    AssistChip({ action = "move" }, { Text("Mover") })
                    AssistChip({ action = "copy" }, { Text("Copiar") })
                    AssistChip({ action = "delete" }, { Text("Excluir", color = BfColors.Danger) })
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Fechar") } },
        containerColor = BfColors.Surface,
    )
    when (action) {
        "verify" -> TextInputDialog("Verificar integridade", "SHA-256 oficial (opcional)", "", "Verificar",
            help = "Cole o SHA-256 publicado pelo site oficial. Sem ele, o app procura um arquivo de checksum ao lado da ISO (ex.: SHA256SUMS). Sempre confere a estrutura.",
            onConfirm = { vm.verifyImage(e.path, it.ifBlank { null }); action = null; onClose() }, onDismiss = { action = null })
        "rename" -> TextInputDialog("Renomear", "Novo nome", e.name, "Renomear", onConfirm = { vm.renameImage(e.path, it); action = null; onClose() }, onDismiss = { action = null })
        "move" -> TextInputDialog("Mover para a pasta", "Pasta de destino", "/" + (info?.os?.category?.folder ?: ""), "Mover",
            help = "Sugestões: /Windows, /Linux, /Ferramentas, /Resgate, /Diagnostico — ou qualquer outra pasta.",
            onConfirm = { vm.moveImage(e.path, it); action = null; onClose() }, onDismiss = { action = null })
        "copy" -> TextInputDialog("Copiar para a pasta", "Pasta de destino", e.folder, "Copiar", onConfirm = { vm.copyImage(e.path, it); action = null; onClose() }, onDismiss = { action = null })
        "delete" -> ConfirmDialog("Excluir ${e.name}?", "A imagem será apagada do pendrive (${Format.bytes(e.size)}).", "Excluir", danger = true,
            onConfirm = { vm.deleteImage(e.path); action = null; onClose() }, onDismiss = { action = null })
    }
}

@Composable
private fun AddIsoDialog(count: Int, onConfirm: (String, String?) -> Unit, onDismiss: () -> Unit) {
    var dir by remember { mutableStateOf("/") }
    var sha by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adicionar $count imagem(ns)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(dir, { dir = it }, label = { Text("Pasta no pendrive") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (listOf("/") + ImageCategory.entries.map { "/" + it.folder }).forEach { d -> AssistChip({ dir = d }, { Text(d) }) }
                }
                if (count == 1) OutlinedTextField(sha, { sha = it.trim() }, label = { Text("SHA-256 oficial (opcional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), textStyle = MonoStyle)
                Text("A cópia é feita em espaço contíguo sempre que possível. Se o SHA-256 informado não conferir, a cópia é removida.",
                    style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
            }
        },
        confirmButton = { Button(onClick = { onConfirm(dir.ifBlank { "/" }, sha.takeIf { it.length == 64 }) }) { Text("Copiar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        containerColor = BfColors.Surface,
    )
}

@Composable
private fun OrganizeDialog(proposal: List<Pair<String, String>>, onApply: (List<Pair<String, String>>) -> Unit, onDismiss: () -> Unit) {
    val chosen = remember(proposal) { mutableStateListOf<Pair<String, String>>().apply { addAll(proposal) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sugestão de organização") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("As pastas são apenas sugestões. Desmarque o que não quiser mover. Nada é copiado — os arquivos só mudam de pasta.",
                    style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
                LazyColumn {
                    items(proposal) { p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(p in chosen, { if (it) chosen += p else chosen -= p })
                            Text("${p.first}\n→ ${p.second}", style = MonoStyle)
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onApply(chosen.toList()) }, enabled = chosen.isNotEmpty()) { Text("Mover ${chosen.size}") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        containerColor = BfColors.Surface,
    )
}
