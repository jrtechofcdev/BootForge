package dev.bootforge.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bootforge.app.data.Format
import dev.bootforge.app.ui.EditorItem
import dev.bootforge.app.ui.EditorSort
import dev.bootforge.app.ui.EditorUi
import dev.bootforge.app.ui.IsoEditorViewModel
import dev.bootforge.app.ui.ViewerState
import dev.bootforge.app.ui.components.Badge
import dev.bootforge.app.ui.components.BfTopBar
import dev.bootforge.app.ui.components.InfoRow
import dev.bootforge.app.ui.theme.MonoStyle
import dev.bootforge.app.ui.components.Notice
import dev.bootforge.app.ui.components.SectionCard
import dev.bootforge.app.ui.screens.ventoy.ConfirmDialog
import dev.bootforge.app.ui.screens.ventoy.TextInputDialog
import dev.bootforge.app.ui.theme.BfColors
import dev.bootforge.isoedit.BootStatus
import dev.bootforge.isoedit.PendingChange
import dev.bootforge.isoedit.RebuildMode

/** Ações da tela (a tela não guarda regra de negócio). */
class IsoEditorActions(
    val onOpen: () -> Unit = {},
    val onNavigate: (String) -> Unit = {},
    val onUp: () -> Unit = {},
    val onItem: (EditorItem) -> Unit = {},
    val onToggle: (String) -> Unit = {},
    val onItemMenu: (EditorItem, String) -> Unit = { _, _ -> },
    val onSort: (EditorSort) -> Unit = {},
    val onSearch: (String) -> Unit = {},
    val onUndo: () -> Unit = {},
    val onRedo: () -> Unit = {},
    val onPending: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onCommand: (String) -> Unit = {},
)

private val ITEM_COMMANDS = listOf("Ver/editar", "Propriedades", "Renomear", "Substituir", "Extrair", "Mover", "Copiar", "Excluir")

/** Diálogos possíveis da tela. */
private sealed interface Dlg {
    data object Mkdir : Dlg
    data class Rename(val item: EditorItem) : Dlg
    data class Delete(val paths: List<String>) : Dlg
    data class Pick(val paths: List<String>, val copy: Boolean) : Dlg
    data class Props(val path: String) : Dlg
    data object Pending : Dlg
    data object SaveMode : Dlg
    data object Discard : Dlg
    data object Close : Dlg
    data object DeleteOriginal : Dlg
}

@Composable
fun IsoEditorScreen(vm: IsoEditorViewModel, onBack: () -> Unit, initialUri: android.net.Uri? = null) {
    // imagem vinda da biblioteca de downloads (Etapa 6)
    LaunchedEffect(initialUri) { if (initialUri != null && vm.ui.value.summary == null) vm.open(initialUri) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    var dlg by remember { mutableStateOf<Dlg?>(null) }
    var replaceTarget by remember { mutableStateOf<String?>(null) }
    var extractPaths by remember { mutableStateOf<List<String>>(emptyList()) }

    val openIso = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::open) }
    val addFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.addFiles(it) }
    val addFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::addFolder) }
    val replace = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> val t = replaceTarget; if (u != null && t != null) vm.replaceFile(t, u) }
    val extractTo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let { u -> vm.extract(extractPaths, u) } }
    val saveTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-iso9660-image")) { if (it != null) vm.save(it) else vm.cancelPreview() }
    val copyTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-iso9660-image")) { it?.let(vm::copyOriginal) }

    val baseName = ui.summary?.name?.substringBeforeLast('.') ?: "imagem"
    fun selectionOr(item: EditorItem?) = if (ui.selection.isNotEmpty()) ui.selection.toList() else listOfNotNull(item?.path)

    Column(Modifier.fillMaxSize()) {
        BfTopBar("Editor de ISO", { if (ui.summary != null && ui.history.isNotEmpty()) dlg = Dlg.Close else onBack() }, ui.summary?.name ?: "Explorar e editar imagens sem alterar o original")
        IsoEditorContent(
            ui,
            IsoEditorActions(
                onOpen = { openIso.launch(arrayOf("application/x-iso9660-image", "application/octet-stream", "*/*")) },
                onNavigate = vm::navigate, onUp = vm::up,
                onItem = { if (ui.selection.isNotEmpty()) vm.toggleSelection(it.path) else if (it.isDir) vm.navigate(it.path) else vm.openViewer(it.path) },
                onToggle = vm::toggleSelection,
                onItemMenu = { item, cmd ->
                    when (cmd) {
                        "Ver/editar" -> if (item.isDir) vm.navigate(item.path) else vm.openViewer(item.path)
                        "Propriedades" -> dlg = Dlg.Props(item.path)
                        "Renomear" -> dlg = Dlg.Rename(item)
                        "Substituir" -> { replaceTarget = item.path; replace.launch(arrayOf("*/*")) }
                        "Extrair" -> { extractPaths = selectionOr(item); extractTo.launch(null) }
                        "Mover" -> dlg = Dlg.Pick(selectionOr(item), copy = false)
                        "Copiar" -> dlg = Dlg.Pick(selectionOr(item), copy = true)
                        "Excluir" -> dlg = Dlg.Delete(selectionOr(item))
                    }
                },
                onSort = vm::setSort, onSearch = vm::search, onUndo = vm::undo, onRedo = vm::redo,
                onPending = { dlg = Dlg.Pending }, onSave = { dlg = Dlg.SaveMode },
                onCommand = { c ->
                    when (c) {
                        "mkdir" -> dlg = Dlg.Mkdir
                        "addFiles" -> addFiles.launch(arrayOf("*/*"))
                        "addFolder" -> addFolder.launch(null)
                        "extractAll" -> { extractPaths = emptyList(); extractTo.launch(null) }
                        "extractSel" -> { extractPaths = ui.selection.toList(); extractTo.launch(null) }
                        "moveSel" -> dlg = Dlg.Pick(ui.selection.toList(), copy = false)
                        "copySel" -> dlg = Dlg.Pick(ui.selection.toList(), copy = true)
                        "deleteSel" -> dlg = Dlg.Delete(ui.selection.toList())
                        "clearSel" -> vm.clearSelection()
                        "selectAll" -> vm.selectAll()
                        "copyOriginal" -> copyTo.launch("$baseName-copia.iso")
                        "close" -> if (ui.history.isNotEmpty()) dlg = Dlg.Close else vm.close()
                    }
                },
            ),
        )
    }

    ui.message?.let { msg ->
        LaunchedEffect(msg) { kotlinx.coroutines.delay(3500); vm.consumeMessage() }
    }

    ui.viewer?.let { v -> ViewerDialog(v, onSave = { vm.saveText(v.path, it) }, onDismiss = vm::closeViewer) }
    ui.preview?.let { p ->
        AlertDialog(
            onDismissRequest = vm::cancelPreview,
            title = { Text("Gerar nova ISO") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoRow("Modo", p.mode.title)
                    InfoRow("Tamanho final", Format.bytes(p.totalBytes))
                    InfoRow("Rótulo", p.volumeId)
                    Text(p.bootSummary, style = MaterialTheme.typography.bodySmall)
                    p.warnings.forEach { Notice(it) }
                    Text("A ISO original não é alterada. Escolha onde salvar a nova; no fim ela é relida e validada.", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
                }
            },
            confirmButton = { Button(onClick = { saveTo.launch("$baseName-editada.iso") }) { Text("Escolher onde salvar") } },
            dismissButton = { TextButton(onClick = vm::cancelPreview) { Text("Cancelar") } },
            containerColor = BfColors.Surface,
        )
    }
    ui.saved?.let { s -> SavedDialog(s, onDeleteOriginal = { dlg = Dlg.DeleteOriginal }, onDismiss = vm::dismissSaved) }

    when (val d = dlg) {
        Dlg.Mkdir -> TextInputDialog("Nova pasta", "Nome", onConfirm = { vm.mkdir(it); dlg = null }, onDismiss = { dlg = null })
        is Dlg.Rename -> TextInputDialog("Renomear", "Novo nome", d.item.name, onConfirm = { vm.rename(d.item.path, it); dlg = null }, onDismiss = { dlg = null })
        is Dlg.Delete -> ConfirmDialog("Excluir da edição?", "${d.paths.size} item(ns) serão removidos da NOVA ISO. A imagem original não muda e você pode desfazer.\n\n" + d.paths.take(8).joinToString("\n"), "Excluir", true, { vm.delete(d.paths); dlg = null }, { dlg = null })
        is Dlg.Pick -> DirPickerDialog(if (d.copy) "Copiar para…" else "Mover para…", allDirs(vm), onPick = { dest -> if (d.copy) vm.copy(d.paths, dest) else vm.move(d.paths, dest); dlg = null }, onDismiss = { dlg = null })
        is Dlg.Props -> AlertDialog(
            onDismissRequest = { dlg = null }, title = { Text("Propriedades") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { vm.properties(d.path).forEach { (k, v) -> InfoRow(k, v) } } },
            confirmButton = { TextButton(onClick = { dlg = null }) { Text("Fechar") } }, containerColor = BfColors.Surface,
        )
        Dlg.Pending -> PendingDialog(ui, onUndo = vm::undo, onRedo = vm::redo, onDiscard = { dlg = Dlg.Discard }, onDismiss = { dlg = null })
        Dlg.SaveMode -> SaveModeDialog(ui, onPlan = { mode, label -> vm.prepareSave(mode, label); dlg = null }, onDismiss = { dlg = null })
        Dlg.Discard -> ConfirmDialog("Descartar alterações?", "Todas as ${ui.history.size} alterações desta sessão serão desfeitas.", "Descartar", true, { vm.discardAll(); dlg = null }, { dlg = null })
        Dlg.Close -> ConfirmDialog("Fechar a imagem?", "Há ${ui.history.size} alteração(ões) não salvas em uma nova ISO. Elas serão perdidas.", "Fechar sem salvar", true, { vm.close(); dlg = null; onBack() }, { dlg = null })
        Dlg.DeleteOriginal -> ConfirmDialog("Excluir a ISO original?", "A nova ISO foi gerada e validada. A original será EXCLUÍDA do armazenamento. Esta ação não pode ser desfeita.", "Excluir original", true, { vm.deleteOriginal(); dlg = null; vm.dismissSaved() }, { dlg = null })
        null -> Unit
    }
}

private fun allDirs(vm: IsoEditorViewModel): List<String> {
    val out = mutableListOf("/")
    vm.ui.value.summary ?: return out
    return out + vm.dirPaths()
}

/** Conteúdo da tela (sem estado próprio; também usado nas capturas automáticas). */
@Composable
fun IsoEditorContent(ui: EditorUi, actions: IsoEditorActions) {
    val summary = ui.summary
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ui.message?.let { Notice(it, BfColors.Steel) }
        if (summary == null) {
            Spacer(Modifier.size(4.dp))
            when {
                ui.loading != null -> Loading(ui.loading)
                else -> {
                    ui.error?.let { Notice(it, BfColors.Danger) }
                    SectionCard(title = "Abrir uma imagem", icon = Icons.Filled.FolderOpen) {
                        Text(
                            "Escolha um arquivo .iso do celular ou de um pendrive/cartão reconhecido pelo Android. " +
                                "Você pode navegar, ver e extrair arquivos (modo leitura) e também adicionar, substituir, renomear, mover e excluir (modo edição).",
                            style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim,
                        )
                        Text(
                            "*A imagem original nunca é alterada:* as mudanças ficam pendentes (com desfazer/refazer) até você gerar uma NOVA ISO, que é validada no fim.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = actions.onOpen, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Abrir ISO") }
                    }
                }
            }
            return@Column
        }

        // resumo + barra de ferramentas
        SummaryCard(ui)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.onUndo, enabled = ui.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Desfazer") }
            IconButton(onClick = actions.onRedo, enabled = ui.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Refazer") }
            FilterChip(selected = ui.pending.isNotEmpty(), onClick = actions.onPending, label = { Text(if (ui.pending.isEmpty()) "Sem alterações" else "${ui.pending.size} alteração(ões)") })
            Spacer(Modifier.weight(1f))
            Button(onClick = actions.onSave, enabled = summary.blockers.isEmpty()) { Icon(Icons.Filled.Save, null); Spacer(Modifier.width(6.dp)); Text("Gerar ISO") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionChip(Icons.Filled.CreateNewFolder, "Nova pasta") { actions.onCommand("mkdir") }
            ActionChip(Icons.Filled.NoteAdd, "Adicionar arquivos") { actions.onCommand("addFiles") }
            ActionChip(Icons.Filled.Folder, "Adicionar pasta") { actions.onCommand("addFolder") }
            ActionChip(Icons.Filled.Download, "Extrair tudo") { actions.onCommand("extractAll") }
            ActionChip(Icons.Filled.ContentCopy, "Copiar original") { actions.onCommand("copyOriginal") }
            ActionChip(Icons.Filled.Error, "Fechar") { actions.onCommand("close") }
        }

        // caminho, pesquisa e ordenação
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.onUp, enabled = ui.cwd != "/") { Icon(Icons.Filled.ArrowUpward, "Pasta acima") }
            Text(ui.cwd, style = MonoStyle, maxLines = 1, overflow = TextOverflow.StartEllipsis, modifier = Modifier.weight(1f))
            var sortOpen by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { sortOpen = true }) { Text(ui.sort.label) }
                DropdownMenu(sortOpen, { sortOpen = false }) {
                    EditorSort.entries.forEach { s -> DropdownMenuItem(text = { Text(s.label) }, onClick = { actions.onSort(s); sortOpen = false }) }
                }
            }
        }
        OutlinedTextField(
            ui.query, actions.onSearch, singleLine = true, modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Filled.Search, null) }, placeholder = { Text("Pesquisar em toda a imagem") },
        )
        if (ui.selection.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${ui.selection.size} selecionado(s)", style = MaterialTheme.typography.labelLarge)
                ActionChip(Icons.Filled.Download, "Extrair") { actions.onCommand("extractSel") }
                ActionChip(Icons.Filled.DriveFileMove, "Mover") { actions.onCommand("moveSel") }
                ActionChip(Icons.Filled.ContentCopy, "Copiar") { actions.onCommand("copySel") }
                ActionChip(Icons.Filled.Delete, "Excluir") { actions.onCommand("deleteSel") }
                ActionChip(Icons.Filled.CheckCircle, "Todos") { actions.onCommand("selectAll") }
                TextButton(onClick = { actions.onCommand("clearSel") }) { Text("Limpar") }
            }
        }
        ui.loading?.let { Loading(it) }
        val list = ui.searchResults ?: ui.items
        if (list.isEmpty()) Text(if (ui.searchResults != null) "Nada encontrado." else "Pasta vazia.", color = BfColors.TextDim)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(list, key = { it.path }) { item ->
                ItemRow(item, ui.selection.isNotEmpty(), item.path in ui.selection, ui.searchResults != null, actions)
            }
        }
    }
}

@Composable
private fun SummaryCard(ui: EditorUi) {
    val s = ui.summary ?: return
    var open by remember { mutableStateOf(false) }
    SectionCard(Modifier.clickable { open = !open }, title = s.name, icon = Icons.Filled.EditNote) {
        Text("${Format.bytes(s.size)} · ${s.format}", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
        if (open) {
            InfoRow("Rótulo", s.volumeId)
            InfoRow("Conteúdo", "${s.files} arquivos, ${s.dirs} pastas")
            InfoRow("Inicialização", s.boots.ifEmpty { listOf("nenhuma (imagem de dados)") }.joinToString("\n"))
            InfoRow("Híbrida (pendrive)", if (s.hybrid) "sim (MBR/GPT)" else "não")
            RebuildMode.entries.forEach { m -> InfoRow(m.title, s.modes[m]?.let { "indisponível: $it" } ?: if (m == s.recommended) "disponível (recomendado)" else "disponível") }
            s.warnings.forEach { Notice(it) }
        } else Text("Toque para ver detalhes da imagem", style = MaterialTheme.typography.labelSmall, color = BfColors.Steel)
        s.blockers.forEach { Notice(it, BfColors.Danger) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemRow(item: EditorItem, selecting: Boolean, selected: Boolean, showPath: Boolean, actions: IsoEditorActions) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (selected) BfColors.ForgeDim else BfColors.Surface)
            .combinedClickable(onClick = { actions.onItem(item) }, onLongClick = { actions.onToggle(item.path) })
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) Checkbox(selected, { actions.onToggle(item.path) })
        val icon = when { item.isDir -> Icons.Filled.Folder; item.isLink -> Icons.Filled.Link; item.isBoot -> Icons.Filled.Bolt; else -> Icons.AutoMirrored.Filled.InsertDriveFile }
        Icon(icon, null, tint = when { item.isDir -> BfColors.Forge; item.isBoot -> BfColors.Warning; else -> BfColors.Steel })
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (if (showPath) item.path + " · " else "") + if (item.isDir) (if (item.children == 1) "1 item" else "${item.children} itens") else Format.bytes(item.size),
                style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim, maxLines = 1, overflow = TextOverflow.StartEllipsis,
            )
        }
        if (item.isNew) Badge("Novo", BfColors.Success)
        if (item.isBoot) Badge("Boot", BfColors.Warning)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Mais") }
            DropdownMenu(menu, { menu = false }) {
                ITEM_COMMANDS.filter { !(item.isDir && it == "Substituir") }.forEach { c ->
                    DropdownMenuItem(text = { Text(c) }, onClick = { menu = false; actions.onItemMenu(item, c) })
                }
            }
        }
    }
}

@Composable
private fun ActionChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(label) }
}

@Composable
private fun ViewerDialog(v: ViewerState, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember(v) { mutableStateOf((v as? ViewerState.Text)?.text ?: "") }
    val changed = v is ViewerState.Text && text != v.text
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(v.path.substringAfterLast('/')) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${v.path} · ${Format.bytes(v.size)}", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
                when (v) {
                    is ViewerState.Text -> OutlinedTextField(text, { text = it }, textStyle = MonoStyle, modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 420.dp))
                    is ViewerState.Hex -> SelectionContainer { Text(v.dump, style = MonoStyle, modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) }
                }
                if (v is ViewerState.Text) Text("Salvar guarda o texto na edição (a ISO original não muda).", style = MaterialTheme.typography.labelSmall, color = BfColors.TextDim)
            }
        },
        confirmButton = { if (v is ViewerState.Text) Button(onClick = { onSave(text) }, enabled = changed) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
        containerColor = BfColors.Surface,
    )
}

@Composable
private fun DirPickerDialog(title: String, dirs: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(dirs.sortedBy { it.lowercase() }) { d ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(d) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Folder, null, tint = BfColors.Forge); Spacer(Modifier.width(8.dp)); Text(d, style = MonoStyle)
                    }
                }
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }, containerColor = BfColors.Surface,
    )
}

@Composable
private fun PendingDialog(ui: EditorUi, onUndo: () -> Unit, onRedo: () -> Unit, onDiscard: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Alterações pendentes") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (ui.pending.isEmpty()) Text("Nenhuma diferença em relação à imagem original.", color = BfColors.TextDim)
                ui.pending.forEach { p ->
                    val color = when (p.kind) { PendingChange.Kind.ADDED -> BfColors.Success; PendingChange.Kind.MODIFIED -> BfColors.Warning; PendingChange.Kind.REMOVED -> BfColors.Danger }
                    Row { Badge(p.kind.label, color); Spacer(Modifier.width(6.dp)); Text("${p.path}  ${p.detail}", style = MaterialTheme.typography.bodySmall) }
                }
                if (ui.history.isNotEmpty()) {
                    Text("Histórico (${ui.history.size})", style = MaterialTheme.typography.titleSmall)
                    ui.history.forEachIndexed { i, h -> Text("${i + 1}. $h", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onUndo, enabled = ui.canUndo) { Text("Desfazer") }
                    OutlinedButton(onClick = onRedo, enabled = ui.canRedo) { Text("Refazer") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
        dismissButton = { TextButton(onClick = onDiscard, enabled = ui.history.isNotEmpty()) { Text("Descartar tudo", color = BfColors.Danger) } },
        containerColor = BfColors.Surface,
    )
}

@Composable
private fun SaveModeDialog(ui: EditorUi, onPlan: (RebuildMode, String?) -> Unit, onDismiss: () -> Unit) {
    val s = ui.summary ?: return
    var mode by remember { mutableStateOf(s.recommended) }
    var label by remember { mutableStateOf(s.volumeId) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Como gerar a nova ISO?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RebuildMode.entries.forEach { m ->
                    val reason = s.modes[m]
                    Row(verticalAlignment = Alignment.Top) {
                        RadioButton(mode == m, { mode = m }, enabled = reason == null)
                        Column(Modifier.padding(top = 10.dp)) {
                            Text(m.title + if (m == s.recommended) " (recomendado)" else "", style = MaterialTheme.typography.titleSmall)
                            Text(m.summary, style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
                            reason?.let { Text("Indisponível: $it", style = MaterialTheme.typography.bodySmall, color = BfColors.Danger) }
                        }
                    }
                }
                OutlinedTextField(label, { label = it.take(32) }, label = { Text("Rótulo do volume") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Mudar o rótulo pode impedir o boot de sistemas que procuram o disco pelo nome (ex.: CDLABEL=). Na dúvida, mantenha.", style = MaterialTheme.typography.labelSmall, color = BfColors.Warning)
            }
        },
        confirmButton = { Button(onClick = { onPlan(mode, label.takeIf { it != s.volumeId }) }, enabled = s.modes[mode] == null) { Text("Continuar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        containerColor = BfColors.Surface,
    )
}

@Composable
private fun SavedDialog(s: dev.bootforge.app.ui.SaveReport, onDeleteOriginal: () -> Unit, onDismiss: () -> Unit) {
    val r = s.report
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (r.ok) Icons.Filled.CheckCircle else Icons.Filled.Error, null, tint = if (r.ok) BfColors.Success else BfColors.Danger) },
        title = { Text(if (r.ok) "ISO gerada e validada" else "Validação encontrou problemas") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoRow("Arquivo", s.name)
                InfoRow("Modo", s.mode.title)
                InfoRow("SHA-256", s.sha256, mono = true)
                Notice(
                    r.boot.label + when (r.boot) {
                        BootStatus.VERIFIED -> ". A prova definitiva é iniciar num PC ou máquina virtual."
                        BootStatus.FAILED -> ". Não use esta ISO para instalar sistemas."
                        BootStatus.NOT_BOOTABLE -> "."
                    },
                    when (r.boot) { BootStatus.VERIFIED -> BfColors.Success; BootStatus.FAILED -> BfColors.Danger; else -> BfColors.Steel },
                )
                r.checks.forEach { c ->
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(if (c.ok) Icons.Filled.CheckCircle else Icons.Filled.Error, null, Modifier.size(16.dp), tint = if (c.ok) BfColors.Success else BfColors.Danger)
                        Spacer(Modifier.width(6.dp))
                        Column { Text(c.title, style = MaterialTheme.typography.bodySmall); if (c.detail.isNotBlank()) Text(c.detail, style = MaterialTheme.typography.labelSmall, color = BfColors.TextDim) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
        dismissButton = { if (r.ok) TextButton(onClick = onDeleteOriginal) { Text("Substituir: excluir a original", color = BfColors.Danger) } },
        containerColor = BfColors.Surface,
    )
}
