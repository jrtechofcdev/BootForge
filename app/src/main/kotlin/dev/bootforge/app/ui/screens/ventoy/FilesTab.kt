package dev.bootforge.app.ui.screens.ventoy

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bootforge.app.data.Format
import dev.bootforge.app.ui.FileSort
import dev.bootforge.app.ui.VentoyViewModel
import dev.bootforge.app.ui.components.CapacityBar
import dev.bootforge.app.ui.components.InfoRow
import dev.bootforge.app.ui.screens.Loading
import dev.bootforge.app.ui.theme.BfColors
import dev.bootforge.app.ui.theme.MonoStyle
import dev.bootforge.fs.exfat.ExFatEntry
import kotlinx.coroutines.launch

/** Gerenciador de arquivos da partição de dados do Ventoy. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilesTab(vm: VentoyViewModel) {
    val st by vm.files.collectAsStateWithLifecycle()
    val analysis by vm.analysis.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf<String?>(null) }
    var pendingUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pendingTree by remember { mutableStateOf<Uri?>(null) }
    var props by remember { mutableStateOf<Map<String, String>?>(null) }
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (st.entries.isEmpty()) vm.listDir(st.path) }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) { pendingUris = uris; dialog = "conflict-files" }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { pendingTree = uri; dialog = "conflict-folder" }
    }
    val exportTo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.export(st.selection.toList(), uri)
    }

    Column(Modifier.fillMaxSize()) {
        // ---- barra: caminho, espaço, ações
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (st.totalBytes > 0) {
                CapacityBar(st.totalBytes - st.freeBytes, st.totalBytes)
                Text("Livre: ${Format.bytes(st.freeBytes)} de ${Format.bytes(st.totalBytes)}", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
            }
            OutlinedTextField(st.query, vm::search, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Pesquisar no pendrive inteiro…") })
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.listDir(st.path.substringBeforeLast('/').ifEmpty { "/" }) }, enabled = st.path != "/") { Icon(Icons.Filled.ArrowUpward, "Subir") }
                Text(st.path, style = MonoStyle, modifier = Modifier.weight(1f))
                androidx.compose.foundation.layout.Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Mais ações") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Nova pasta") }, { menu = false; dialog = "mkdir" })
                        DropdownMenuItem({ Text("Novo arquivo de texto") }, { menu = false; dialog = "newtext" })
                        DropdownMenuItem({ Text("Copiar arquivos do celular") }, { menu = false; pickFiles.launch(arrayOf("*/*")) })
                        DropdownMenuItem({ Text("Copiar pasta do celular") }, { menu = false; pickFolder.launch(null) })
                        DropdownMenuItem({ Text("Selecionar tudo") }, { menu = false; vm.selectAll() })
                        FileSort.entries.forEach { s -> DropdownMenuItem({ Text("Ordenar por ${s.label}" + if (st.sort == s) " ✓" else "") }, { menu = false; vm.setSort(s) }) }
                        DropdownMenuItem({ Text("Analisar espaço e duplicados") }, { menu = false; vm.analyze() })
                        DropdownMenuItem({ Text("Atualizar") }, { menu = false; vm.listDir(st.path) })
                    }
                }
            }
            // barra de seleção / colar
            if (st.selection.isNotEmpty() || st.clipboard != null) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (st.selection.isNotEmpty()) {
                        AssistChip({ vm.copyToClipboard(cut = false) }, { Text("Copiar (${st.selection.size})") })
                        AssistChip({ vm.copyToClipboard(cut = true) }, { Text("Recortar") })
                        AssistChip({ exportTo.launch(null) }, { Text("Para o celular") })
                        if (st.selection.size == 1) {
                            AssistChip({ target = st.selection.first(); dialog = "rename" }, { Text("Renomear") })
                            AssistChip({ vm.shareFile(st.selection.first()) }, { Text("Compartilhar") })
                            AssistChip({ scope.launch { props = runCatching { vm.properties(st.selection.first()) }.getOrNull() } }, { Text("Propriedades") })
                        }
                        AssistChip({ dialog = "delete" }, { Text("Excluir", color = BfColors.Danger) })
                        AssistChip({ vm.clearSelection() }, { Text("Limpar seleção") })
                    }
                    st.clipboard?.let { c -> AssistChip({ vm.paste() }, { Text("${if (c.cut) "Mover" else "Colar"} ${c.paths.size} aqui") }) }
                }
            }
        }

        if (st.loading) Loading("Lendo pasta…")
        val list: List<Pair<String, ExFatEntry>> = st.searchResults ?: st.entries.map { VentoyViewModel.child(st.path, it.name) to it }
        val sorted = list.sortedWith(
            compareBy<Pair<String, ExFatEntry>> { !it.second.isDirectory }.then(
                when (st.sort) {
                    FileSort.NAME -> compareBy { it.second.name.lowercase() }
                    FileSort.SIZE -> compareByDescending { it.second.size }
                    FileSort.TYPE -> compareBy { VentoyViewModel.typeLabel(it.second.name) }
                    FileSort.DATE -> compareByDescending { it.second.modifiedMillis ?: 0 }
                },
            ),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            if (sorted.isEmpty() && !st.loading) item { Text(if (st.searchResults != null) "Nada encontrado." else "Pasta vazia.", Modifier.padding(16.dp), color = BfColors.TextDim) }
            items(sorted, key = { it.first }) { (path, e) ->
                val selected = path in st.selection
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (selected) BfColors.ForgeDim else BfColors.Background)
                        .combinedClickable(
                            onClick = { if (st.selection.isNotEmpty()) vm.toggleSelection(path) else if (e.isDirectory) vm.listDir(path) else vm.toggleSelection(path) },
                            onLongClick = { vm.toggleSelection(path) },
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(selected, { vm.toggleSelection(path) })
                    val icon = when {
                        e.isDirectory -> Icons.Filled.Folder
                        e.name.substringAfterLast('.').lowercase() in setOf("iso", "img", "wim", "vhd", "vhdx", "vtoy", "efi") -> Icons.Filled.Album
                        else -> Icons.AutoMirrored.Filled.InsertDriveFile
                    }
                    Icon(icon, null, tint = if (e.isDirectory) BfColors.Forge else BfColors.Steel, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        val sub = if (st.searchResults != null) path.substringBeforeLast('/').ifEmpty { "/" } + " • " else ""
                        Text(sub + (if (e.isDirectory) "Pasta" else "${VentoyViewModel.typeLabel(e.name)} • ${Format.bytes(e.size)}") +
                            (e.modifiedMillis?.let { " • " + Format.dateTime(it) } ?: ""), style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
                    }
                }
            }
        }
    }

    when (dialog) {
        "mkdir" -> TextInputDialog("Nova pasta", "Nome", onConfirm = { vm.mkdir(it); dialog = null }, onDismiss = { dialog = null })
        "newtext" -> NewTextDialog(onConfirm = { n, c -> vm.newTextFile(n, c); dialog = null }, onDismiss = { dialog = null })
        "rename" -> TextInputDialog("Renomear", "Novo nome", target?.substringAfterLast('/') ?: "", "Renomear",
            onConfirm = { target?.let { t -> vm.rename(t, it) }; dialog = null }, onDismiss = { dialog = null })
        "delete" -> ConfirmDialog("Excluir ${st.selection.size} item(ns)?",
            st.selection.joinToString("\n") + "\n\nPastas são excluídas com todo o conteúdo. Não há lixeira.", "Excluir", danger = true,
            onConfirm = { vm.delete(st.selection.toList()); vm.clearSelection(); dialog = null }, onDismiss = { dialog = null })
        "conflict-files" -> ConflictDialog({ vm.importFiles(pendingUris, it); dialog = null }, { dialog = null })
        "conflict-folder" -> ConflictDialog({ p -> pendingTree?.let { vm.importFolder(it, p) }; dialog = null }, { dialog = null })
    }
    props?.let { p ->
        AlertDialog(onDismissRequest = { props = null }, title = { Text("Propriedades") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { p.forEach { (k, v) -> InfoRow(k, v) } } },
            confirmButton = { TextButton(onClick = { props = null }) { Text("Fechar") } }, containerColor = BfColors.Surface)
    }
    analysis?.let { a ->
        AlertDialog(onDismissRequest = { }, title = { Text("Análise do pendrive (${a.files} arquivos)") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item { Text("Espaço por tipo", style = MaterialTheme.typography.titleMedium) }
                    items(a.byType) { (t, s) -> InfoRow(t, Format.bytes(s)) }
                    item { Text("Duplicados (mesmo conteúdo SHA-256): ${a.duplicates.size} grupo(s)", style = MaterialTheme.typography.titleMedium) }
                    items(a.duplicates) { g -> Text(g.joinToString("\n") { it.first } + "\n(${Format.bytes(g.first().second)} cada)", style = MonoStyle, color = BfColors.TextDim) }
                }
            },
            confirmButton = { TextButton(onClick = { vm.clearAnalysis() }) { Text("Fechar") } }, containerColor = BfColors.Surface)
    }
}

@Composable
private fun NewTextDialog(onConfirm: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("notas.txt") }
    var content by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Novo arquivo de texto") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(content, { content = it }, label = { Text("Conteúdo (UTF-8)") }, modifier = Modifier.fillMaxWidth(), textStyle = MonoStyle, minLines = 5)
            }
        },
        confirmButton = { androidx.compose.material3.Button(onClick = { onConfirm(name, content) }, enabled = name.isNotBlank()) { Text("Criar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        containerColor = BfColors.Surface,
    )
}
