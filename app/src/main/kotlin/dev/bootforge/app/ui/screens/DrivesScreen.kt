package dev.bootforge.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bootforge.app.data.Format
import dev.bootforge.app.ui.AppViewModel
import dev.bootforge.app.ui.DriveReport
import dev.bootforge.app.ui.InspectState
import dev.bootforge.app.ui.components.Badge
import dev.bootforge.app.ui.components.BfTopBar
import dev.bootforge.app.ui.components.ConfirmWriteDialog
import dev.bootforge.app.ui.components.InfoRow
import dev.bootforge.app.ui.components.Notice
import dev.bootforge.app.ui.components.SectionCard
import dev.bootforge.app.ui.theme.BfColors
import dev.bootforge.app.ui.theme.MonoStyle
import dev.bootforge.partition.FsType
import dev.bootforge.usb.UsbDriveInfo

/** Lista de pendrives conectados. */
@Composable
fun DrivesScreen(vm: AppViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val drives by vm.drives.drives.collectAsStateWithLifecycle()
    val event by vm.drives.events.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        BfTopBar("Pendrives", onBack, "Dispositivos USB de armazenamento")
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            event?.let { item { Notice(it, BfColors.Steel, Icons.Filled.Info) } }
            if (drives.isEmpty()) {
                item {
                    SectionCard(title = "Nenhum pendrive encontrado", icon = Icons.Filled.Usb) {
                        Text(
                            "Conecte o pendrive pelo adaptador USB OTG. Se nada aparecer, verifique se o seu celular " +
                                "suporta OTG (alguns modelos exigem ativar a opção \"OTG\" nas configurações).",
                            style = MaterialTheme.typography.bodyMedium, color = BfColors.TextDim,
                        )
                        OutlinedButton(onClick = vm.drives::refresh) {
                            Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Procurar novamente")
                        }
                    }
                }
            }
            items(drives, key = { it.deviceName }) { d -> DriveCard(d, vm, onOpen) }
        }
    }
}

@Composable
private fun DriveCard(d: UsbDriveInfo, vm: AppViewModel, onOpen: (String) -> Unit) {
    SectionCard(title = d.displayName, icon = Icons.Filled.Usb) {
        InfoRow("VID:PID", "%04X:%04X".format(d.vendorId, d.productId), mono = true)
        InfoRow("Caminho", d.deviceName, mono = true)
        if (d.hasPermission) {
            InfoRow("Nº de série", d.serial)
            Button(onClick = { onOpen(d.deviceName) }, modifier = Modifier.fillMaxWidth()) { Text("Inspecionar") }
        } else {
            Text(
                "O Android exige sua autorização para o app acessar este pendrive.",
                style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim,
            )
            Button(onClick = { vm.drives.requestPermission(d.deviceName) }, modifier = Modifier.fillMaxWidth()) { Text("Permitir acesso") }
        }
    }
}

/** Detalhes de um pendrive + ferramentas de diagnóstico (prova de conceito). */
@Composable
fun DriveDetailScreen(vm: AppViewModel, id: String, onBack: () -> Unit, onVentoy: () -> Unit) {
    val state by vm.inspect.collectAsStateWithLifecycle()
    val drives by vm.drives.drives.collectAsStateWithLifecycle()
    LaunchedEffect(id) {
        val s = vm.inspect.value
        if (!(s is InspectState.Ready && s.report.info.deviceName == id)) vm.inspect(id)
    }
    Column(Modifier.fillMaxSize()) {
        BfTopBar("Detalhes do pendrive", onBack)
        when (val s = state) {
            is InspectState.Loading, InspectState.Idle -> Loading("Lendo tabela de partições e sistemas de arquivos…")
            is InspectState.Failed -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Notice("Não foi possível ler o pendrive: ${s.message}", BfColors.Danger, Icons.Filled.Error)
                Button(onClick = { vm.inspect(id) }) { Text("Tentar novamente") }
            }
            is InspectState.Ready -> {
                // O seletor de arquivos do sistema (SAF) fica aqui, na camada ligada à Activity.
                var pendingUri by remember { mutableStateOf<android.net.Uri?>(null) }
                var confirmCapacity by remember { mutableStateOf(false) }
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pendingUri = uri }
                DriveDetails(
                    s.report, drives.size, onVentoy,
                    onBenchmark = { vm.readBenchmark(s.report.info) },
                    onPickImage = { picker.launch(arrayOf("*/*")) },
                    onCapacity = { confirmCapacity = true },
                )
                if (confirmCapacity) ConfirmWriteDialog(
                    title = "Testar a capacidade real?",
                    report = s.report,
                    connectedDrives = drives.size,
                    destructive = false,
                    message = "Grava marcas em ~128 pontos do pendrive, confere e DEVOLVE o conteúdo original (seus dados ficam como estavam). " +
                        "Leva menos de um minuto. Não remova o pendrive durante o teste.",
                    confirmLabel = "Testar",
                    onConfirm = { confirmCapacity = false; vm.capacityTest(s.report.info, s.report.capacityBytes) },
                    onDismiss = { confirmCapacity = false },
                )
                pendingUri?.let { uri ->
                    ConfirmWriteDialog(
                        title = "Gravar imagem RAW?",
                        report = s.report,
                        connectedDrives = drives.size,
                        destructive = true,
                        message = "O arquivo selecionado será gravado a partir do setor 0 e depois conferido por releitura.",
                        confirmLabel = "Gravar",
                        onConfirm = { pendingUri = null; vm.writeRawImage(s.report.info, uri, s.report.capacityBytes) },
                        onDismiss = { pendingUri = null },
                    )
                }
            }
        }
    }
}

@Composable
fun Loading(text: String) {
    Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(color = BfColors.Forge)
        Spacer(Modifier.width(16.dp))
        Text(text, color = BfColors.TextDim)
    }
}

@Composable
fun DriveDetails(
    r: DriveReport,
    connected: Int,
    onVentoy: () -> Unit,
    onBenchmark: () -> Unit,
    onPickImage: () -> Unit,
    onCapacity: () -> Unit = {},
) {
    var showHex by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(title = r.info.displayName, icon = Icons.Filled.Usb) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge(r.layout.scheme.label, BfColors.Steel)
                if (r.ventoy.isVentoy) Badge("Ventoy ${r.ventoy.version}", BfColors.Forge)
            }
            InfoRow("Fabricante", r.info.manufacturer)
            InfoRow("Modelo", r.info.product)
            InfoRow("Nº de série", r.info.serial)
            InfoRow("VID:PID", "%04X:%04X".format(r.info.vendorId, r.info.productId), mono = true)
            InfoRow("Estado", "Conectado • acesso direto por setores (USB OTG)", valueColor = BfColors.Success)
        }

        SectionCard(title = "Capacidade", icon = Icons.Filled.Storage) {
            InfoRow("Total", Format.bytes(r.capacityBytes))
            InfoRow("Setores", "${r.blockCount} × ${r.blockSize} bytes", mono = true)
            r.ventoy.data?.let { d ->
                if (d.totalBytes != null && d.freeBytes != null) {
                    InfoRow("Usado (dados)", Format.bytes(d.totalBytes!! - d.freeBytes!!))
                    InfoRow("Livre (dados)", Format.bytes(d.freeBytes))
                }
            }
        }

        SectionCard(title = "Partições", icon = Icons.Filled.Memory) {
            InfoRow("Esquema", r.layout.scheme.label)
            r.layout.wholeDiskFs?.takeIf { r.layout.partitions.isEmpty() }?.let { InfoRow("Sem partição", it.type.label) }
            if (r.layout.partitions.isEmpty() && r.layout.wholeDiskFs == null) Text("Nenhuma partição encontrada.", color = BfColors.TextDim)
            r.layout.partitions.forEach { p ->
                Column(Modifier.padding(top = 6.dp)) {
                    Text(
                        "#${p.number}  ${p.fs?.type?.label ?: "?"}  ${Format.bytes(p.sectorCount * r.blockSize)}" +
                            (p.name?.let { "  \"$it\"" } ?: "") + (if (p.bootable) "  [ativa]" else ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("${p.typeDescription} • setores ${p.startLba}–${p.endLba}" + (p.fs?.label?.let { " • rótulo $it" } ?: ""), style = MonoStyle, color = BfColors.TextDim)
                }
            }
            r.layout.warnings.forEach { Notice(it, BfColors.Warning) }
        }

        SectionCard(title = "Inicialização identificada", icon = Icons.Filled.RocketLaunch) {
            val bootCode = r.sector0.copyOfRange(0, 440).any { it.toInt() != 0 }
            InfoRow("Código de boot no MBR", if (bootCode) "presente (BIOS/Legacy)" else "ausente")
            InfoRow("Ventoy", if (r.ventoy.isVentoy) "versão ${r.ventoy.version}" else "não encontrado")
            val iso = r.layout.wholeDiskFs?.type == FsType.ISO9660 || r.layout.partitions.any { it.fs?.type == FsType.ISO9660 }
            if (iso) InfoRow("Imagem ISO gravada", "ISO9660 detectado (provável ISO híbrida)")
            if (r.ventoy.isVentoy) {
                Button(onClick = onVentoy, modifier = Modifier.fillMaxWidth()) { Text("Abrir no módulo Ventoy") }
            }
        }

        SectionCard(title = "Diagnóstico (prova de conceito)", icon = Icons.Filled.Science, accent = BfColors.Steel) {
            Text(
                "Valida o acesso de baixo nível pelo USB OTG neste aparelho: leitura de setores com velocidade real e " +
                    "gravação de imagem com verificação por releitura (SHA-256).",
                style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim,
            )
            OutlinedButton(onClick = onBenchmark, modifier = Modifier.fillMaxWidth()) { Text("Teste de leitura (não altera nada)") }
            OutlinedButton(onClick = onCapacity, modifier = Modifier.fillMaxWidth()) { Text("Testar capacidade real (detecta pendrive falsificado)") }
            Button(
                onClick = onPickImage,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = BfColors.Danger),
            ) { Text("Gravar imagem RAW (.img/.iso híbrida) — apaga o pendrive") }
            Notice(
                "A gravação RAW copia o arquivo setor a setor, como o \"dd\". Serve para imagens híbridas (a maioria das ISOs Linux). " +
                    "ISOs do Windows NÃO funcionam assim — para elas, use o Ventoy.",
                BfColors.Steel, Icons.Filled.Info,
            )
            TextButton(onClick = { showHex = !showHex }) { Text(if (showHex) "Ocultar setor 0" else "Ver setor 0 (hexadecimal)") }
            if (showHex) {
                Text(
                    Format.hexdump(r.sector0),
                    style = MonoStyle, color = BfColors.TextDim,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}
