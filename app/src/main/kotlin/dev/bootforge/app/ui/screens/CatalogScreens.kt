package dev.bootforge.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bootforge.app.data.CenterState
import dev.bootforge.app.data.EntryState
import dev.bootforge.app.data.Format
import dev.bootforge.app.data.JobState
import dev.bootforge.app.data.JobStatus
import dev.bootforge.app.ui.CatalogViewModel
import dev.bootforge.app.ui.components.Badge
import dev.bootforge.app.ui.components.BfTopBar
import dev.bootforge.app.ui.components.InfoRow
import dev.bootforge.app.ui.components.Notice
import dev.bootforge.app.ui.components.SectionCard
import dev.bootforge.app.ui.screens.ventoy.ConfirmDialog
import dev.bootforge.app.ui.screens.ventoy.TextInputDialog
import dev.bootforge.app.ui.theme.BfColors
import dev.bootforge.app.ui.theme.MonoStyle
import dev.bootforge.catalog.Category
import dev.bootforge.catalog.ItemStatus
import dev.bootforge.catalog.LibraryItem
import dev.bootforge.catalog.ResolvedImage
import dev.bootforge.catalog.Verification

// ====================================================================== Catálogo

class CatalogActions(
    val onFilter: (Category?) -> Unit = {},
    val onCheck: () -> Unit = {},
    val onUpdateCatalog: () -> Unit = {},
    val onDownload: (ResolvedImage) -> Unit = {},
    val onOpenPage: (String) -> Unit = {},
    val onDownloads: () -> Unit = {},
    val onVerifyFile: () -> Unit = {},
)

@Composable
fun CatalogScreen(vm: CatalogViewModel, onBack: () -> Unit, onDownloads: () -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf<Category?>(null) }
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { if (st.checkedAt == null && st.entries.values.none { it.loading }) vm.checkVersions() }
    LaunchedEffect(st.message) { if (st.message != null) { kotlinx.coroutines.delay(5000); vm.consumeMessage() } }
    Column(Modifier.fillMaxSize()) {
        BfTopBar("Catálogo", onBack, "Sistemas das fontes oficiais")
        CatalogContent(st, filter, CatalogActions(
            onFilter = { filter = it }, onCheck = vm::checkVersions, onUpdateCatalog = vm::updateCatalog, onDownload = { vm.download(it); onDownloads() },
            onOpenPage = { url -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
            onDownloads = onDownloads, onVerifyFile = onDownloads,
        ))
    }
}

@Composable
fun CatalogContent(st: CenterState, filter: Category?, actions: CatalogActions) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        st.message?.let { Notice(it, BfColors.Steel) }
        Text(
            "Versões consultadas direto nas fontes oficiais (APIs, feeds e páginas dos projetos). Catálogo ${st.catalogOrigin}" +
                (st.checkedAt?.let { " · consultado em ${Format.dateTime(it)}" } ?: ""),
            style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = actions.onCheck) { Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Consultar versões") }
            OutlinedButton(onClick = actions.onUpdateCatalog) { Icon(Icons.Filled.CloudDownload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Atualizar catálogo") }
            OutlinedButton(onClick = actions.onDownloads) { Icon(Icons.Filled.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Downloads" + if (st.activeCount > 0) " (${st.activeCount})" else "") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(filter == null, { actions.onFilter(null) }, label = { Text("Todos") })
            Category.entries.forEach { c -> FilterChip(filter == c, { actions.onFilter(c) }, label = { Text(c.label) }) }
        }
        st.byCategory(filter).forEach { e -> SystemCard(e, st, actions) }
        Text(
            "Só são usadas fontes oficiais. Sem checksum oficial, o app avisa que a autenticidade não pôde ser comprovada.",
            style = MaterialTheme.typography.labelSmall, color = BfColors.TextDim,
        )
    }
}

@Composable
private fun SystemCard(e: EntryState, st: CenterState, actions: CatalogActions) {
    val s = e.system
    val icon = when (s.category) { Category.WINDOWS -> Icons.Filled.Album; Category.LINUX -> Icons.Filled.RocketLaunch; Category.TOOLS -> Icons.Filled.VerifiedUser }
    SectionCard(title = s.name, icon = icon) {
        Text("${s.vendor} · ${s.arch}", style = MaterialTheme.typography.labelMedium, color = BfColors.Steel)
        Text(s.description, style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
        val r = e.resolved
        when {
            e.loading -> { LinearProgressIndicator(Modifier.fillMaxWidth(), color = BfColors.Forge); Text("Consultando a fonte oficial…", style = MaterialTheme.typography.bodySmall) }
            e.error != null -> Notice("Não foi possível consultar agora: ${e.error}", BfColors.Danger)
            r != null && s.browserOnly -> {
                Badge("Download pelo navegador", BfColors.Warning)
                s.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { actions.onOpenPage(s.officialPage) }) { Icon(Icons.Filled.Language, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Abrir site oficial") }
                    TextButton(onClick = actions.onVerifyFile) { Text("Verificar ISO baixada") }
                }
            }
            r != null -> {
                InfoRow("Versão mais recente", r.version)
                InfoRow("Tamanho", r.sizeBytes?.let { Format.bytes(it) } ?: "não informado")
                InfoRow("Servidor", r.host)
                VerificationBadge(r.verification)
                Text(r.verification.detail, style = MaterialTheme.typography.labelSmall, color = BfColors.TextDim)
                val inLibrary = st.library.firstOrNull { it.systemId == s.id && it.version == r.version }
                val job = st.jobs.firstOrNull { it.req.systemId == s.id }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        inLibrary != null -> OutlinedButton(onClick = actions.onDownloads) { Text("Já baixado ✓") }
                        job != null -> OutlinedButton(onClick = actions.onDownloads) { Text("${job.status.label}…") }
                        else -> Button(onClick = { actions.onDownload(r) }) { Icon(Icons.Filled.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Baixar") }
                    }
                    TextButton(onClick = { actions.onOpenPage(s.officialPage) }) { Text("Página oficial") }
                }
            }
            else -> Text("Toque em \"Consultar versões\".", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
        }
    }
}

@Composable
private fun VerificationBadge(v: Verification) = Badge(v.label, when (v) { Verification.SIGNED -> BfColors.Success; Verification.OFFICIAL_CHECKSUM -> BfColors.Steel; Verification.NONE -> BfColors.Warning })

// ====================================================================== Downloads e biblioteca

class DownloadsActions(
    val onPause: (String) -> Unit = {}, val onResume: (String) -> Unit = {}, val onCancel: (String) -> Unit = {},
    val onWrite: (LibraryItem) -> Unit = {}, val onEdit: (LibraryItem) -> Unit = {}, val onVentoy: (LibraryItem) -> Unit = {},
    val onExport: (LibraryItem) -> Unit = {}, val onRemove: (LibraryItem) -> Unit = {}, val onVerifyFile: () -> Unit = {}, val onCatalog: () -> Unit = {},
)

@Composable
fun DownloadsScreen(
    vm: CatalogViewModel, onBack: () -> Unit, onCatalog: () -> Unit,
    onWrite: (Uri) -> Unit, onEdit: (Uri) -> Unit, onVentoy: (Uri, String?) -> Unit,
) {
    val st by vm.state.collectAsStateWithLifecycle()
    val ext by vm.external.collectAsStateWithLifecycle()
    var exportItem by remember { mutableStateOf<LibraryItem?>(null) }
    var removeItem by remember { mutableStateOf<LibraryItem?>(null) }
    var askHash by remember { mutableStateOf(false) }
    var expected by remember { mutableStateOf("") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-iso9660-image")) { u -> val i = exportItem; if (u != null && i != null) vm.export(i, u) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) vm.verifyExternal(u, expected) }
    LaunchedEffect(st.message) { if (st.message != null) { kotlinx.coroutines.delay(5000); vm.consumeMessage() } }

    Column(Modifier.fillMaxSize()) {
        BfTopBar("Downloads", onBack, "Fila, progresso e biblioteca de imagens")
        DownloadsContent(st, freeSpace(vm), DownloadsActions(
            onPause = vm::pause, onResume = vm::resume, onCancel = vm::cancel,
            onWrite = { onWrite(vm.uriFor(it)) }, onEdit = { onEdit(vm.uriFor(it)) }, onVentoy = { onVentoy(vm.uriFor(it), it.sha256) },
            onExport = { exportItem = it; export.launch(it.fileName) }, onRemove = { removeItem = it },
            onVerifyFile = { askHash = true }, onCatalog = onCatalog,
        ))
    }
    if (askHash) TextInputDialog(
        "Verificar ISO baixada", "SHA-256 ou SHA-512 oficial",
        help = "Cole o checksum publicado na página oficial (ex.: a Microsoft mostra o SHA-256 em \"Verify your download\"). Depois escolha o arquivo: ele é lido, não copiado.",
        confirmLabel = "Escolher arquivo", onConfirm = { expected = it; askHash = false; pick.launch(arrayOf("*/*")) }, onDismiss = { askHash = false },
    )
    ext?.let { c ->
        AlertDialog(
            onDismissRequest = vm::dismissExternal,
            title = { Text(if (c.matches) "Checksum confere ✓" else "Checksum NÃO confere") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoRow("Arquivo", c.name); InfoRow("Algoritmo", c.algorithm)
                    InfoRow("Calculado", c.actual, mono = true); InfoRow("Informado", c.expected, mono = true)
                    Notice(if (c.matches) "O arquivo é idêntico ao publicado." else "Não use este arquivo: corrompido, incompleto ou diferente do oficial.", if (c.matches) BfColors.Success else BfColors.Danger)
                }
            },
            confirmButton = { TextButton(onClick = vm::dismissExternal) { Text("Fechar") } }, containerColor = BfColors.Surface,
        )
    }
    removeItem?.let { i -> ConfirmDialog("Excluir da biblioteca?", "\"${i.fileName}\" (${Format.bytes(i.sizeBytes)}) será apagado do armazenamento do app.", "Excluir", true, { vm.remove(i); removeItem = null }, { removeItem = null }) }
}

private fun freeSpace(vm: CatalogViewModel): String = runCatching {
    val d = dev.bootforge.app.data.AppGraph.downloads.dir
    "${d.absolutePath} · livre: ${Format.bytes(d.usableSpace)}"
}.getOrDefault("")

@Composable
fun DownloadsContent(st: CenterState, storage: String, actions: DownloadsActions) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        st.message?.let { Notice(it, BfColors.Steel) }
        if (st.jobs.isNotEmpty()) {
            Text("Em andamento", style = MaterialTheme.typography.titleMedium)
            st.jobs.forEach { JobCard(it, actions) }
        }
        Text("Biblioteca (${st.library.size})", style = MaterialTheme.typography.titleMedium)
        if (st.library.isEmpty()) Notice("Nenhuma imagem baixada ainda. Escolha um sistema no Catálogo.", BfColors.Steel)
        st.library.forEach { LibraryCard(it, actions) }
        SectionCard(title = "Verificar ISO baixada fora do app", icon = Icons.Filled.VerifiedUser) {
            Text("Para imagens baixadas pelo navegador (como o Windows): compara o arquivo com o checksum oficial que você colar.", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
            OutlinedButton(onClick = actions.onVerifyFile) { Text("Verificar um arquivo") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(storage, style = MaterialTheme.typography.labelSmall, color = BfColors.TextDim, modifier = Modifier.weight(1f))
            TextButton(onClick = actions.onCatalog) { Text("Catálogo") }
        }
    }
}

@Composable
private fun JobCard(j: JobState, actions: DownloadsActions) {
    val name = j.req.fileName
    SectionCard(title = "${j.req.title} ${j.req.version ?: ""}".trim(), icon = Icons.Filled.Download) {
        val total = j.progress?.bytesTotal ?: j.req.expectedSize
        val frac = if (total != null && total > 0) (j.bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
        Row(verticalAlignment = Alignment.CenterVertically) {
            Badge(j.status.label, when (j.status) { JobStatus.RUNNING -> BfColors.Forge; JobStatus.FAILED -> BfColors.Danger; else -> BfColors.Steel })
            Spacer(Modifier.width(8.dp))
            j.progress?.stage?.takeIf { it != j.status.label }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = BfColors.TextDim) }
        }
        LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth(), color = BfColors.Forge)
        Text(
            "${Format.bytes(j.bytes)} de ${total?.let { Format.bytes(it) } ?: "?"} (${(frac * 100).toInt()}%)" +
                (j.progress?.takeIf { j.status == JobStatus.RUNNING }?.let { " · ${Format.speed(it.bytesPerSecond)} · faltam ${Format.eta(it.etaSeconds)}" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
        )
        j.message?.let { Notice(it, BfColors.Danger) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (j.status) {
                JobStatus.RUNNING, JobStatus.QUEUED -> SmallAction(Icons.Filled.Pause, "Pausar") { actions.onPause(name) }
                JobStatus.PAUSED, JobStatus.FAILED -> SmallAction(Icons.Filled.PlayArrow, "Continuar") { actions.onResume(name) }
            }
            SmallAction(Icons.Filled.Stop, "Cancelar") { actions.onCancel(name) }
        }
    }
}

@Composable
private fun LibraryCard(i: LibraryItem, actions: DownloadsActions) {
    SectionCard(title = "${i.title} ${i.version ?: ""}".trim(), icon = Icons.Filled.Album) {
        Text("${i.fileName} · ${Format.bytes(i.sizeBytes)} · ${Format.dateTime(i.addedAt)}", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
        Badge(i.status.label, statusColor(i.status))
        Text("SHA-256 ${i.sha256}", style = MonoStyle, color = BfColors.TextDim)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallAction(Icons.Filled.Album, "Gravar no pendrive") { actions.onWrite(i) }
            SmallAction(Icons.Filled.RocketLaunch, "Copiar para o Ventoy") { actions.onVentoy(i) }
            SmallAction(Icons.Filled.EditNote, "Abrir no editor") { actions.onEdit(i) }
            SmallAction(Icons.Filled.Save, "Salvar no celular") { actions.onExport(i) }
            SmallAction(Icons.Filled.Delete, "Excluir") { actions.onRemove(i) }
        }
    }
}

private fun statusColor(s: ItemStatus): Color = when (s) { ItemStatus.SIGNED_OK -> BfColors.Success; ItemStatus.CHECKSUM_OK, ItemStatus.USER_CHECKSUM_OK -> BfColors.Steel; ItemStatus.UNVERIFIED -> BfColors.Warning }

@Composable
private fun SmallAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(label) }
}
