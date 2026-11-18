package dev.bootforge.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bootforge.app.Routes
import dev.bootforge.app.ui.AppViewModel
import dev.bootforge.app.ui.components.ModuleCard
import dev.bootforge.app.ui.theme.BfColors

/** Módulo da tela inicial. [stage] != null → ainda não implementado (aparece bloqueado). */
private data class Module(val title: String, val description: String, val icon: ImageVector, val route: String?, val stage: String?)

private val modules = listOf(
    Module("Gravar ISO", "Gravação inteligente de imagens Windows e Linux", Icons.Filled.Album, null, "Etapa 3"),
    Module("Ventoy", "Instalar, ISOs, arquivos, temas e ferramentas", Icons.Filled.RocketLaunch, Routes.VENTOY, null),
    Module("Editor de ISO", "Explorar, extrair e reconstruir imagens", Icons.Filled.EditNote, null, "Etapa 5"),
    Module("Catálogo", "Sistemas operacionais de fontes oficiais", Icons.Filled.CloudDownload, null, "Etapa 6"),
    Module("Pendrives", "Detectar, inspecionar e testar dispositivos", Icons.Filled.Usb, Routes.DRIVES, null),
    Module("Downloads", "Downloads com retomada e checksum", Icons.Filled.Download, null, "Etapa 6"),
    Module("Histórico", "Operações realizadas neste aparelho", Icons.Filled.History, Routes.HISTORY, null),
    Module("Configurações", "Sobre, licenças e integridade", Icons.Filled.Settings, Routes.SETTINGS, null),
)

@Composable
fun HomeScreen(vm: AppViewModel, onNavigate: (String) -> Unit) {
    val drives by vm.drives.drives.collectAsStateWithLifecycle()
    HomeContent(drives.size, onNavigate)
}

/** Conteúdo da tela inicial (sem estado: recebe tudo por parâmetro). */
@Composable
fun HomeContent(driveCount: Int, onNavigate: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { Header() }
        item(span = { GridItemSpan(maxLineSpan) }) {
            DriveStatusCard(count = driveCount, onClick = { onNavigate(Routes.DRIVES) })
        }
        items(modules) { m ->
            ModuleCard(m.title, m.description, m.icon, availableIn = m.stage) { m.route?.let(onNavigate) }
        }
    }
}

@Composable
private fun Header() {
    Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Boot", style = MaterialTheme.typography.headlineMedium)
            Text("Forge", style = MaterialTheme.typography.headlineMedium, color = BfColors.Forge)
        }
        Text(
            "CREATE. MANAGE. BOOT.",
            style = MaterialTheme.typography.labelSmall,
            color = BfColors.TextDim,
        )
    }
}

@Composable
private fun DriveStatusCard(count: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(BfColors.ForgeDim, BfColors.Surface)))
            .border(1.dp, BfColors.Outline, shape)
            .clickable(onClick = onClick)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(BfColors.Background),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Usb, null, tint = if (count > 0) BfColors.Success else BfColors.TextDim) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when (count) {
                    0 -> "Nenhum pendrive conectado"
                    1 -> "1 pendrive conectado"
                    else -> "$count pendrives conectados"
                },
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (count == 0) "Conecte um pendrive pelo adaptador USB OTG" else "Toque para inspecionar",
                style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = BfColors.TextDim)
    }
}
