package dev.bootforge.app.ui.screens.ventoy

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bootforge.app.data.Format
import dev.bootforge.app.ui.HubLoad
import dev.bootforge.app.ui.HubState
import dev.bootforge.app.ui.VentoyViewModel
import dev.bootforge.app.ui.components.Badge
import dev.bootforge.app.ui.components.BfTopBar
import dev.bootforge.app.ui.components.CapacityBar
import dev.bootforge.app.ui.components.InfoRow
import dev.bootforge.app.ui.components.Notice
import dev.bootforge.app.ui.components.SectionCard
import dev.bootforge.app.ui.screens.Loading
import dev.bootforge.app.ui.theme.BfColors
import dev.bootforge.app.ui.theme.MonoStyle

/** As 8 áreas da aba Ventoy pedidas no projeto (seção 10.2). */
enum class VentoyArea(val title: String) {
    OVERVIEW("Visão geral"), FILES("Arquivos"), ISOS("ISOs"), CONFIG("Configurações"),
    THEMES("Temas"), TOOLS("Ferramentas"), UPDATES("Atualizações"), DEVICE("Dispositivo"),
}

/** Central do Ventoy: escolha do pendrive e as 8 áreas de gerenciamento. */
@Composable
fun VentoyHubScreen(
    vm: VentoyViewModel,
    onBack: () -> Unit,
    onInstall: (String) -> Unit,
    onDeviceDetails: (String) -> Unit,
    /** Imagem da biblioteca de downloads para copiar para o pendrive (Etapa 6). */
    importUri: android.net.Uri? = null,
    importSha: String? = null,
) {
    var importDone by rememberSaveable { mutableStateOf(false) }
    val drives by vm.drives.drives.collectAsStateWithLifecycle()
    val device by vm.device.collectAsStateWithLifecycle()
    val hub by vm.hub.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    LaunchedEffect(Unit) { vm.share.collect { context.startActivity(it) } }
    LaunchedEffect(drives) {
        val single = drives.singleOrNull()
        if ((device == null || drives.none { it.deviceName == device }) && single != null && single.hasPermission) vm.select(single.deviceName)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            BfTopBar("Ventoy", onBack, "Pacote do app: Ventoy ${runCatching { vm.embedded.version }.getOrDefault("?")}")
            if (drives.isEmpty()) {
                Column(Modifier.padding(16.dp)) { Notice("Conecte um pendrive pelo USB OTG para começar.", BfColors.Steel, Icons.Filled.Info) }
                return@Column
            }
            if (drives.size > 1 || device == null) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    drives.forEach { d ->
                        FilterChip(selected = d.deviceName == device, onClick = {
                            if (d.hasPermission) vm.select(d.deviceName) else vm.drives.requestPermission(d.deviceName)
                        }, label = { Text(d.displayName + if (d.hasPermission) "" else " (autorizar)") })
                    }
                }
            }
            when (val h = hub) {
                HubLoad.Idle -> Column(Modifier.padding(16.dp)) {
                    Notice("Escolha o pendrive acima. Se pedir, autorize o acesso.", BfColors.Steel, Icons.Filled.Lock)
                }
                HubLoad.Loading -> Loading("Lendo o pendrive…")
                is HubLoad.Failed -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Notice(h.message, BfColors.Danger, Icons.Filled.Error)
                    Button(onClick = vm::reloadHub) { Text("Tentar novamente") }
                }
                is HubLoad.Ready -> if (!h.hub.ventoy.isVentoy) NotVentoy(h.hub, onInstall) else {
                    if (importUri != null && !importDone) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            SectionCard(title = "Copiar imagem baixada", icon = Icons.Filled.Info) {
                                Text("\"${importUri.lastPathSegment}\" será copiada para a raiz do Ventoy e conferida (SHA-256) depois da cópia.", style = MaterialTheme.typography.bodySmall)
                                Button(onClick = { vm.addImages(listOf(importUri), "/", importSha); importDone = true }, modifier = Modifier.fillMaxWidth()) { Text("Copiar para ${h.hub.info.displayName}") }
                            }
                        }
                    }
                    Areas(vm, h.hub, onInstall, onDeviceDetails)
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

@Composable
private fun NotVentoy(hub: HubState, onInstall: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(title = "Ventoy não encontrado em ${hub.info.displayName}", icon = Icons.Filled.RocketLaunch) {
            Text("Verificações realizadas (mesmas regras do Ventoy2Disk oficial):", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
            hub.ventoy.checks.forEach { Text((if (it.passed) "✓ " else "✗ ") + it.description, style = MaterialTheme.typography.bodySmall) }
            Button(onClick = { onInstall(hub.info.deviceName) }, modifier = Modifier.fillMaxWidth()) { Text("Instalar o Ventoy neste pendrive") }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun Areas(vm: VentoyViewModel, hub: HubState, onInstall: (String) -> Unit, onDeviceDetails: (String) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val areas = VentoyArea.entries
    Column(Modifier.fillMaxSize()) {
        PrimaryScrollableTabRow(selectedTabIndex = tab, containerColor = BfColors.Background, edgePadding = 12.dp) {
            areas.forEachIndexed { i, a -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(a.title) }) }
        }
        when (areas[tab]) {
            VentoyArea.OVERVIEW -> OverviewTab(hub, vm.embedded.let { runCatching { it.version }.getOrNull() }) { tab = it.ordinal }
            VentoyArea.FILES -> FilesTab(vm)
            VentoyArea.ISOS -> IsosTab(vm)
            VentoyArea.CONFIG -> ConfigTab(vm)
            VentoyArea.THEMES -> ThemesTab(vm)
            VentoyArea.TOOLS -> ToolsTab(vm, hub)
            VentoyArea.UPDATES -> UpdatesTab(vm, hub, onInstall)
            VentoyArea.DEVICE -> DeviceTab(hub, onDeviceDetails)
        }
    }
}

@Composable
fun OverviewTab(hub: HubState, embedded: String?, goTo: (VentoyArea) -> Unit) {
    val v = hub.ventoy
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(title = hub.info.displayName, icon = Icons.Filled.RocketLaunch) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge("Ventoy ${v.version}")
                v.style?.let { Badge(it.label, BfColors.Steel) }
                Badge(if (v.secureBootSupport == true) "Secure Boot" else "Sem Secure Boot", if (v.secureBootSupport == true) BfColors.Success else BfColors.Warning)
            }
            val d = v.data
            if (d?.totalBytes != null && d.freeBytes != null) {
                CapacityBar(d.totalBytes!! - d.freeBytes!!, d.totalBytes!!)
                InfoRow("Usado", Format.bytes(d.totalBytes!! - d.freeBytes!!))
                InfoRow("Livre", Format.bytes(d.freeBytes))
            }
            InfoRow("Capacidade do pendrive", Format.bytes(hub.capacityBytes))
            InfoRow("Imagens inicializáveis", d?.bootableImageCount?.toString())
            InfoRow("Arquivos ISO", d?.isoCount?.toString())
            InfoRow("Arquivos / pastas", d?.let { "${it.fileCount ?: "?"} / ${it.directoryCount ?: "?"}" })
            d?.note?.let { Notice(it, BfColors.Steel) }
            v.warnings.forEach { Notice(it, BfColors.Warning) }
            if (embedded != null && v.version != null && embedded != v.version) {
                Notice("O app tem o Ventoy $embedded; o pendrive tem ${v.version}. Veja a aba Atualizações.", BfColors.Steel, Icons.Filled.Info)
            }
        }
        SectionCard(title = "Atalhos", icon = Icons.Filled.Storage) {
            listOf(VentoyArea.ISOS to "Adicionar ou organizar ISOs", VentoyArea.FILES to "Gerenciar arquivos do pendrive",
                VentoyArea.CONFIG to "Editar o ventoy.json", VentoyArea.THEMES to "Trocar o tema do menu",
                VentoyArea.TOOLS to "Verificar instalação, Secure Boot, backup, persistência").forEach { (area, label) ->
                OutlinedButton(onClick = { goTo(area) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
            }
        }
    }
}

@Composable
fun DeviceTab(hub: HubState, onDeviceDetails: (String) -> Unit) {
    val v = hub.ventoy
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(title = "Informações do dispositivo", icon = Icons.Filled.Storage) {
            InfoRow("Fabricante", hub.info.manufacturer)
            InfoRow("Modelo", hub.info.product)
            InfoRow("Nº de série", hub.info.serial)
            InfoRow("VID:PID", "%04X:%04X".format(hub.info.vendorId, hub.info.productId), mono = true)
            InfoRow("Capacidade", Format.bytes(hub.capacityBytes))
            InfoRow("Tabela", v.layout.scheme.label)
            InfoRow("UUID do disco Ventoy", v.diskUuid, mono = true)
        }
        SectionCard(title = "Partições") {
            v.layout.partitions.forEach { p ->
                Text("#${p.number} ${p.fs?.type?.label ?: "?"} ${Format.bytes(p.sectorCount * v.layout.sectorSize)} ${p.name ?: ""}", style = MaterialTheme.typography.bodyMedium)
                Text("${p.typeDescription} • setores ${p.startLba}–${p.endLba}", style = MonoStyle, color = BfColors.TextDim)
            }
        }
        OutlinedButton(onClick = { onDeviceDetails(hub.info.deviceName) }, modifier = Modifier.fillMaxWidth()) { Text("Detalhes completos e diagnóstico") }
    }
}
