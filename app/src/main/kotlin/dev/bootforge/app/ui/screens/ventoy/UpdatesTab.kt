package dev.bootforge.app.ui.screens.ventoy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bootforge.app.data.Format
import dev.bootforge.app.ui.HubState
import dev.bootforge.app.ui.VentoyViewModel
import dev.bootforge.app.ui.components.Badge
import dev.bootforge.app.ui.components.InfoRow
import dev.bootforge.app.ui.components.Notice
import dev.bootforge.app.ui.components.SectionCard
import dev.bootforge.app.ui.screens.Loading
import dev.bootforge.app.ui.theme.BfColors
import dev.bootforge.ventoy.VentoyPackage

/** Versões do Ventoy: embutida, baixadas do GitHub oficial e atualização não destrutiva. */
@Composable
fun UpdatesTab(vm: VentoyViewModel, hub: HubState, onInstall: (String) -> Unit) {
    val st by vm.updates.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<VentoyPackage?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(title = "Versões", icon = Icons.Filled.SystemUpdate) {
            InfoRow("No pendrive", hub.ventoy.version)
            InfoRow("Embutida no app", runCatching { vm.embedded.version }.getOrNull())
            Text("Atualizar é o procedimento oficial NÃO destrutivo: regrava o carregador e a VTOYEFI; ISOs e arquivos ficam intactos. " +
                "A escolha de Secure Boot é mantida.", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
            Button(onClick = { confirm = vm.embedded }, modifier = Modifier.fillMaxWidth()) { Text("Atualizar/reinstalar com a versão do app") }
            OutlinedButton(onClick = { onInstall(hub.info.deviceName) }, modifier = Modifier.fillMaxWidth()) { Text("Reinstalação completa (apaga tudo)…") }
        }

        SectionCard(title = "Versões no GitHub oficial", icon = Icons.Filled.CloudDownload) {
            Text("Fonte: github.com/ventoy/Ventoy (releases). O pacote é conferido com o sha256.txt publicado na mesma release.",
                style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
            Button(onClick = vm::loadReleases, modifier = Modifier.fillMaxWidth(), enabled = !st.loading) { Text("Consultar versões") }
            if (st.loading) Loading("Consultando o GitHub…")
            st.error?.let { Notice(it, BfColors.Danger) }
            st.releases?.forEach { r ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Ventoy ${r.version}", style = MaterialTheme.typography.titleMedium)
                        if (r.prerelease) Badge("pré-lançamento", BfColors.Warning)
                        if (r.checksums != null) Badge("sha256 oficial", BfColors.Success) else Badge("sem checksum", BfColors.Warning)
                    }
                    Text("${r.publishedAt.take(10)} • ${r.linuxPackage?.let { Format.bytes(it.size) } ?: "sem pacote Linux"}", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
                    val already = st.downloaded.any { it.version == r.version }
                    if (r.linuxPackage != null && !already) OutlinedButton(onClick = { vm.download(r) }) { Text("Baixar") }
                    if (already) Text("Já baixado", style = MaterialTheme.typography.bodySmall, color = BfColors.Success)
                }
            }
        }

        if (st.downloaded.isNotEmpty()) SectionCard(title = "Pacotes baixados") {
            st.downloaded.sortedByDescending { it.version }.forEach { d ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Ventoy ${d.version}", style = MaterialTheme.typography.titleMedium)
                        if (d.verified) Badge("verificado", BfColors.Success) else Badge("NÃO verificado", BfColors.Danger)
                    }
                    Text("SHA-256 do pacote: ${d.archiveSha256.take(24)}…", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
                    Button(onClick = { confirm = d.pkg }) { Text("Atualizar o pendrive com ${d.version}") }
                }
            }
        }
    }
    confirm?.let { pkg ->
        ConfirmDialog("Atualizar o Ventoy?",
            "Versão ${runCatching { pkg.version }.getOrDefault("?")} (${pkg.origin}). Serão regravados o código de boot, o core.img e a partição VTOYEFI. " +
                "As ISOs e os arquivos da partição de dados são preservados." +
                if (pkg.origin.contains("SEM")) "\n\nATENÇÃO: este pacote não pôde ser conferido com um checksum oficial." else "",
            "Atualizar", onConfirm = { vm.updateWith(pkg); confirm = null }, onDismiss = { confirm = null })
    }
}
