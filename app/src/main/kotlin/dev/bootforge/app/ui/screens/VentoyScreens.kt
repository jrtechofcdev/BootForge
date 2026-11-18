package dev.bootforge.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bootforge.app.data.Format
import dev.bootforge.app.ui.AppViewModel
import dev.bootforge.app.ui.DriveReport
import dev.bootforge.app.ui.InspectState
import dev.bootforge.app.ui.components.Badge
import dev.bootforge.app.ui.components.BfTopBar
import dev.bootforge.app.ui.components.CapacityBar
import dev.bootforge.app.ui.components.ConfirmWriteDialog
import dev.bootforge.app.ui.components.InfoRow
import dev.bootforge.app.ui.components.Notice
import dev.bootforge.app.ui.components.SectionCard
import dev.bootforge.app.ui.theme.BfColors
import dev.bootforge.app.ui.theme.MonoStyle
import dev.bootforge.ventoy.PartitionStyle
import dev.bootforge.ventoy.VentoyInstallOptions
import dev.bootforge.ventoy.VentoyLayout

/** Central do Ventoy: escolha do pendrive, visão geral, ISOs, atualização e instalação. */
@Composable
fun VentoyScreen(vm: AppViewModel, onBack: () -> Unit, onInstall: (String) -> Unit) {
    val drives by vm.drives.drives.collectAsStateWithLifecycle()
    val state by vm.inspect.collectAsStateWithLifecycle()
    val embedded = remember { runCatching { vm.ventoyPackage.version }.getOrNull() }
    var selected by remember { mutableStateOf<String?>(null) }

    // Seleciona automaticamente o pendrive já inspecionado ou o único conectado.
    LaunchedEffect(drives, state) {
        if (selected == null || drives.none { it.deviceName == selected }) {
            selected = (state as? InspectState.Ready)?.report?.info?.deviceName?.takeIf { n -> drives.any { it.deviceName == n } }
                ?: drives.singleOrNull()?.deviceName
        }
    }
    LaunchedEffect(selected) {
        val id = selected ?: return@LaunchedEffect
        val s = vm.inspect.value
        val drive = drives.firstOrNull { it.deviceName == id }
        if (drive?.hasPermission == true && !(s is InspectState.Ready && s.report.info.deviceName == id)) vm.inspect(id)
    }

    Column(Modifier.fillMaxSize()) {
        BfTopBar("Ventoy", onBack, "Pacote embutido: Ventoy ${embedded ?: "indisponível"}")
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (drives.isEmpty()) {
                Notice("Conecte um pendrive pelo USB OTG para começar.", BfColors.Steel, Icons.Filled.Info)
                return@Column
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                drives.forEach { d ->
                    FilterChip(selected = d.deviceName == selected, onClick = { selected = d.deviceName }, label = { Text(d.displayName) })
                }
            }
            val drive = drives.firstOrNull { it.deviceName == selected } ?: return@Column
            if (!drive.hasPermission) {
                Notice("Autorize o acesso ao pendrive para continuar.", BfColors.Warning, Icons.Filled.Lock)
                Button(onClick = { vm.drives.requestPermission(drive.deviceName) }) { Text("Permitir acesso") }
                return@Column
            }
            when (val s = state) {
                is InspectState.Ready -> if (s.report.info.deviceName == drive.deviceName) {
                    VentoyOverview(s.report, drives.size, embedded, onInstall, onUpdate = { vm.updateVentoy(s.report.info, s.report.capacityBytes) })
                } else Loading("Lendo o pendrive…")
                is InspectState.Failed -> {
                    Notice("Falha ao ler o pendrive: ${s.message}", BfColors.Danger, Icons.Filled.Error)
                    Button(onClick = { vm.inspect(drive.deviceName) }) { Text("Tentar novamente") }
                }
                else -> Loading("Procurando instalação do Ventoy…")
            }
        }
    }
}

@Composable
fun VentoyOverview(r: DriveReport, connected: Int, embedded: String?, onInstall: (String) -> Unit, onUpdate: () -> Unit) {
    val v = r.ventoy
    var confirmUpdate by remember { mutableStateOf(false) }

    if (!v.isVentoy) {
        SectionCard(title = "Ventoy não encontrado neste pendrive", icon = Icons.Filled.RocketLaunch) {
            Text("Verificações realizadas (mesmas regras do Ventoy2Disk oficial):", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
            v.checks.forEach { CheckLine(it.description, it.passed) }
            Button(onClick = { onInstall(r.info.deviceName) }, modifier = Modifier.fillMaxWidth()) { Text("Instalar o Ventoy neste pendrive") }
        }
        return
    }

    // ---------------- Visão geral ----------------
    SectionCard(title = "Visão geral", icon = Icons.Filled.RocketLaunch) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Badge("Ventoy ${v.version}")
            v.style?.let { Badge(it.label, BfColors.Steel) }
            if (v.secureBootSupport == true) Badge("Secure Boot", BfColors.Success)
        }
        val d = v.data
        if (d?.totalBytes != null && d.freeBytes != null) {
            CapacityBar(d.totalBytes!! - d.freeBytes!!, d.totalBytes!!)
            InfoRow("Usado", Format.bytes(d.totalBytes!! - d.freeBytes!!))
            InfoRow("Livre", Format.bytes(d.freeBytes))
        }
        InfoRow("Capacidade do pendrive", Format.bytes(r.capacityBytes))
        InfoRow("Partição de dados", "${d?.fsType?.label ?: "?"} \"${d?.label ?: ""}\"")
        InfoRow("Imagens inicializáveis", d?.bootableImageCount?.toString())
        InfoRow("Arquivos ISO", d?.isoCount?.toString())
        InfoRow("Arquivos / pastas", d?.let { "${it.fileCount ?: "?"} / ${it.directoryCount ?: "?"}" })
        d?.note?.let { Notice(it, BfColors.Steel) }
        v.warnings.forEach { Notice(it, BfColors.Warning) }
    }

    // ---------------- ISOs ----------------
    SectionCard(title = "Imagens no pendrive (${r.images.size})", icon = Icons.Filled.Album) {
        if (r.images.isEmpty()) {
            Text("Nenhuma imagem encontrada. Copie arquivos .iso para o pendrive (em qualquer pasta).", color = BfColors.TextDim, style = MaterialTheme.typography.bodySmall)
        }
        r.images.take(200).forEach { img ->
            Column(Modifier.padding(vertical = 2.dp)) {
                Text(img.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium)
                Text("${img.path.substringBeforeLast('/').ifEmpty { "/" }} • ${Format.bytes(img.sizeBytes)}", style = MonoStyle, color = BfColors.TextDim)
            }
        }
        Text("Listagem somente leitura. Copiar, mover, renomear e excluir chegam com o gerenciador de arquivos (Etapa 4).", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
    }

    // ---------------- Atualizações ----------------
    SectionCard(title = "Atualizações e manutenção", icon = Icons.Filled.Tune) {
        InfoRow("Instalado", v.version)
        InfoRow("Disponível no app", embedded)
        val newer = embedded != null && v.version != null && compareVersions(embedded, v.version!!) > 0
        val label = if (newer) "Atualizar para $embedded" else "Reinstalar componentes (preserva arquivos)"
        Text(
            "Procedimento oficial não destrutivo: regrava o carregador de boot e a partição VTOYEFI. A partição de dados (ISOs) não é tocada.",
            style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim,
        )
        Button(onClick = { confirmUpdate = true }, modifier = Modifier.fillMaxWidth(), enabled = v.secureBootSupport != false) { Text(label) }
        OutlinedButton(onClick = { onInstall(r.info.deviceName) }, modifier = Modifier.fillMaxWidth()) { Text("Reinstalação completa (apaga tudo)…") }
        Text("Consulta de versões novas na internet: Etapa 6 (catálogo).", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
    }

    // ---------------- Áreas ----------------
    SectionCard(title = "Áreas do Ventoy", icon = Icons.Filled.ViewList) {
        AreaLine("Visão geral", null)
        AreaLine("Informações do dispositivo", null)
        AreaLine("Atualizações (versão embutida)", null)
        AreaLine("Gerenciador de ISOs (listagem)", null)
        AreaLine("Gerenciador de arquivos (copiar/mover/excluir)", "Etapa 4")
        AreaLine("Configurações (ventoy.json)", "Etapa 4")
        AreaLine("Temas", "Etapa 4")
        AreaLine("Ferramentas (persistência, VentoyVlnk…)", "Etapa 4")
    }

    if (confirmUpdate) {
        ConfirmWriteDialog(
            title = "Atualizar o Ventoy?",
            report = r,
            connectedDrives = connected,
            destructive = false,
            message = "Serão regravados: código de boot (MBR), core.img e a partição VTOYEFI (32 MiB). Suas ISOs e arquivos são preservados.",
            confirmLabel = "Atualizar",
            onConfirm = { confirmUpdate = false; onUpdate() },
            onDismiss = { confirmUpdate = false },
        )
    }
}

@Composable
private fun CheckLine(text: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Error, null, tint = if (ok) BfColors.Success else BfColors.TextDim)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AreaLine(text: String, stage: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (stage == null) Icons.Filled.CheckCircle else Icons.Filled.Lock, null, tint = if (stage == null) BfColors.Success else BfColors.TextDim)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), color = if (stage == null) BfColors.Text else BfColors.TextDim)
        if (stage != null) Badge(stage, BfColors.TextDim)
    }
}

/** Compara versões no formato 1.1.18. */
private fun compareVersions(a: String, b: String): Int {
    val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
    val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val c = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
        if (c != 0) return c
    }
    return 0
}

/** Instalação do Ventoy com as opções oficiais disponíveis. */
@Composable
fun VentoyInstallScreen(vm: AppViewModel, id: String, onBack: () -> Unit) {
    val state by vm.inspect.collectAsStateWithLifecycle()
    val drives by vm.drives.drives.collectAsStateWithLifecycle()
    LaunchedEffect(id) {
        val s = vm.inspect.value
        if (!(s is InspectState.Ready && s.report.info.deviceName == id)) vm.inspect(id)
    }
    Column(Modifier.fillMaxSize()) {
        BfTopBar("Instalar Ventoy", onBack, "Ventoy ${runCatching { vm.ventoyPackage.version }.getOrDefault("?")}")
        val s = state
        if (s !is InspectState.Ready || s.report.info.deviceName != id) {
            if (s is InspectState.Failed) Notice(s.message, BfColors.Danger) else Loading("Lendo o pendrive…")
            return@Column
        }
        InstallForm(s.report, drives.size) { opts -> vm.installVentoy(s.report.info, opts, s.report.capacityBytes) }
    }
}

@Composable
fun InstallForm(r: DriveReport, connected: Int, onInstall: (VentoyInstallOptions) -> Unit) {
    var style by remember { mutableStateOf(if (r.blockCount > VentoyLayout.MBR_MAX_SECTORS) PartitionStyle.GPT else PartitionStyle.MBR) }
    var align by remember { mutableStateOf(true) }
    var reserveText by remember { mutableStateOf("0") }
    var confirm by remember { mutableStateOf(false) }
    val reserve = reserveText.toLongOrNull()
    val options = VentoyInstallOptions(style = style, reserveMiB = reserve ?: -1, align4k = align)
    val layout = runCatching { VentoyLayout.compute(r.blockCount, options) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(title = "Dispositivo", icon = Icons.Filled.Info) {
            InfoRow("Pendrive", r.info.displayName)
            InfoRow("Capacidade", Format.bytes(r.capacityBytes))
            InfoRow("Setor", "${r.blockSize} bytes")
            if (r.blockSize != 512) Notice("Setor nativo de ${r.blockSize} bytes: o Ventoy não suporta este tipo de dispositivo.", BfColors.Danger)
        }

        SectionCard(title = "Estilo de partição", icon = Icons.Filled.Tune) {
            PartitionStyle.entries.forEach { st ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected = style == st, onClick = { style = st }),
                    verticalAlignment = Alignment.Top,
                ) {
                    RadioButton(selected = style == st, onClick = { style = st })
                    Column(Modifier.padding(top = 10.dp)) {
                        Text(st.label, style = MaterialTheme.typography.titleMedium)
                        Text(st.description, style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
                    }
                }
            }
        }

        SectionCard(title = "Opções", icon = Icons.Filled.Tune) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Alinhar partições em 4 KB", style = MaterialTheme.typography.bodyMedium)
                    Text("Recomendado: melhora o desempenho em memórias flash.", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
                }
                Switch(align, { align = it })
            }
            OutlinedTextField(
                reserveText, { reserveText = it.filter(Char::isDigit).take(7) },
                label = { Text("Espaço reservado no fim do disco (MiB)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Suporte a Secure Boot", style = MaterialTheme.typography.bodyMedium)
                    Text("Sempre ativo nesta versão (a VTOYEFI é gravada como no pacote oficial). Também funciona em PCs sem Secure Boot.", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
                }
                Switch(true, {}, enabled = false)
            }
        }

        SectionCard(title = "Resultado previsto", icon = Icons.Filled.Info) {
            layout.onSuccess { l ->
                InfoRow("Partição 1 (exFAT \"Ventoy\")", Format.bytes(l.part1Sectors * 512))
                InfoRow("Partição 2 (VTOYEFI, FAT16)", Format.bytes(l.part2Sectors * 512))
                InfoRow("Setores", "1: ${l.part1Start}–${l.part1End}\n2: ${l.part2Start}–${l.part2End}", mono = true)
            }.onFailure { Notice(it.message ?: "Configuração inválida", BfColors.Danger) }
        }

        Button(
            onClick = { confirm = true },
            enabled = layout.isSuccess && r.blockSize == 512 && reserve != null,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = BfColors.Danger),
        ) { Text("Instalar (apaga todo o pendrive)") }
    }

    if (confirm) {
        ConfirmWriteDialog(
            title = "Instalar o Ventoy?",
            report = r,
            connectedDrives = connected,
            destructive = true,
            message = "O pendrive será particionado (${style.label}), formatado e receberá os componentes originais do Ventoy.",
            confirmLabel = "Instalar",
            onConfirm = { confirm = false; onInstall(options) },
            onDismiss = { confirm = false },
        )
    }
}
