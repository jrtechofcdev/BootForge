package dev.bootforge.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bootforge.app.ui.theme.BfColors
import dev.bootforge.app.ui.theme.MonoStyle

/** Barra superior padrão com botão "voltar". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BfTopBar(title: String, onBack: (() -> Unit)?, subtitle: String? = null) {
    TopAppBar(
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
            }
        },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = BfColors.Background),
    )
}

/** Cartão de seção com título opcional. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector? = null,
    accent: Color = BfColors.Forge,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(BfColors.Surface)
            .border(1.dp, BfColors.Outline, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
        }
        content()
    }
}

/** Linha "rótulo: valor" para informações técnicas. */
@Composable
fun InfoRow(label: String, value: String?, mono: Boolean = false, valueColor: Color = BfColors.Text) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = BfColors.TextDim, modifier = Modifier.weight(0.45f))
        Text(
            value ?: "—",
            style = if (mono) MonoStyle else MaterialTheme.typography.bodyMedium,
            color = valueColor,
            modifier = Modifier.weight(0.55f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Selo pequeno colorido (ex.: "MBR", "Ventoy 1.1.18", "Etapa 3"). */
@Composable
fun Badge(text: String, color: Color = BfColors.Forge) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/**
 * Cartão de módulo da tela inicial.
 * Quando [availableIn] não é nulo, o módulo AINDA NÃO existe: o cartão fica esmaecido,
 * não é clicável e mostra em qual etapa será entregue (nada de botões falsos).
 */
@Composable
fun ModuleCard(
    title: String,
    description: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    availableIn: String? = null,
    onClick: () -> Unit,
) {
    val enabled = availableIn == null
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(BfColors.Surface)
            .border(1.dp, if (enabled) BfColors.Outline else BfColors.Outline.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(16.dp)
            .alpha(if (enabled) 1f else 0.45f),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (enabled) BfColors.ForgeDim else BfColors.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = if (enabled) BfColors.Forge else BfColors.TextDim)
        }
        Spacer(Modifier.height(2.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        Text(description, style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim, maxLines = 2, minLines = 2)
        if (availableIn != null) Badge(availableIn, BfColors.TextDim)
    }
}

/** Barra de capacidade (usado / total). */
@Composable
fun CapacityBar(used: Long, total: Long) {
    val fraction = if (total > 0) (used.toFloat() / total).coerceIn(0f, 1f) else 0f
    LinearProgressIndicator(
        progress = { fraction },
        modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)),
        color = if (fraction > 0.9f) BfColors.Warning else BfColors.Steel,
        trackColor = BfColors.SurfaceHigh,
        strokeCap = StrokeCap.Round,
    )
}

/** Mensagem de aviso/erro/sucesso em destaque. */
@Composable
fun Notice(text: String, color: Color = BfColors.Warning, icon: ImageVector? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = BfColors.Text, fontWeight = FontWeight.Normal)
    }
}
