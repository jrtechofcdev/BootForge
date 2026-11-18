package dev.bootforge.app.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Paleta do BootForge.
 * Para mudar as cores do app inteiro, altere apenas os valores abaixo.
 */
object BfColors {
    val Background = Color(0xFF0B0F17)   // fundo principal (quase preto azulado)
    val Surface = Color(0xFF121826)      // cartões
    val SurfaceHigh = Color(0xFF1A2233)  // cartões em destaque / campos
    val Outline = Color(0xFF263049)      // bordas sutis
    val Forge = Color(0xFFFF7A1A)        // cor da marca (laranja "forja")
    val ForgeDim = Color(0xFF3A2414)     // fundo de selos laranja
    val Steel = Color(0xFF5CC8FF)        // destaque secundário (ciano)
    val Text = Color(0xFFE6EAF2)
    val TextDim = Color(0xFF8C97AD)
    val Success = Color(0xFF3DDC97)
    val Warning = Color(0xFFFFC145)
    val Danger = Color(0xFFFF5A5F)
}

private val scheme = darkColorScheme(
    primary = BfColors.Forge,
    onPrimary = Color(0xFF1A0E05),
    primaryContainer = BfColors.ForgeDim,
    onPrimaryContainer = BfColors.Forge,
    secondary = BfColors.Steel,
    onSecondary = Color(0xFF00202E),
    background = BfColors.Background,
    onBackground = BfColors.Text,
    surface = BfColors.Surface,
    onSurface = BfColors.Text,
    surfaceVariant = BfColors.SurfaceHigh,
    onSurfaceVariant = BfColors.TextDim,
    surfaceContainer = BfColors.Surface,
    surfaceContainerHigh = BfColors.SurfaceHigh,
    surfaceContainerHighest = BfColors.SurfaceHigh,
    outline = BfColors.Outline,
    outlineVariant = BfColors.Outline,
    error = BfColors.Danger,
)

private val typography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.6.sp),
)

/** Fonte monoespaçada para dados técnicos (setores, hashes, hexdump). */
val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)

@Composable
fun BootForgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography) {
        // Cor padrão de textos e ícones: clara (sem isso, textos sem cor explícita saem pretos).
        CompositionLocalProvider(LocalContentColor provides BfColors.Text, content = content)
    }
}
