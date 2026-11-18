package dev.bootforge.app.ui.screens.ventoy

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bootforge.app.ui.ThemePreview
import dev.bootforge.app.ui.VentoyViewModel
import dev.bootforge.app.ui.components.Badge
import dev.bootforge.app.ui.components.Notice
import dev.bootforge.app.ui.components.SectionCard
import dev.bootforge.app.ui.screens.Loading
import dev.bootforge.app.ui.theme.BfColors
import dev.bootforge.app.ui.theme.MonoStyle
import dev.bootforge.ventoy.ThemeInfo

/** Temas do menu do Ventoy: listar, pré-visualizar, aplicar, importar, remover e restaurar. */
@Composable
fun ThemesTab(vm: VentoyViewModel) {
    val st by vm.themes.collectAsStateWithLifecycle()
    var zipUri by remember { mutableStateOf<Uri?>(null) }
    var imageFor by remember { mutableStateOf<ThemeInfo?>(null) }
    var pendingImage by remember { mutableStateOf<Pair<ThemeInfo, Uri>?>(null) }
    var confirmRemove by remember { mutableStateOf<ThemeInfo?>(null) }
    LaunchedEffect(Unit) { if (st.themes.isEmpty()) vm.loadThemes() }
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { zipUri = it }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val t = imageFor; if (uri != null && t != null) pendingImage = t to uri
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) }) { Text("Importar .zip") }
            OutlinedButton(onClick = vm::installOriginalTheme) { Text("Copiar tema original") }
            OutlinedButton(onClick = vm::restoreDefaultTheme) { Text("Usar tema padrão") }
        }
        Text("Sem tema configurado, o Ventoy usa o tema padrão que está na partição VTOYEFI. Temas ficam em /ventoy no pendrive.",
            style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
        if (st.loading) Loading("Procurando temas…")
        if (!st.loading && st.themes.isEmpty()) Notice("Nenhum tema no pendrive. Importe um .zip ou copie o tema original para personalizar.", BfColors.Steel)
        st.themes.forEach { t ->
            SectionCard(title = t.name, icon = Icons.Filled.Palette) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (t.active) Badge("Em uso", BfColors.Success)
                    Badge("${t.images.size} imagem(ns)", BfColors.Steel)
                }
                Text(t.themeFile, style = MonoStyle, color = BfColors.TextDim)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip({ vm.preview(t) }, { Text("Pré-visualizar") })
                    if (!t.active) AssistChip({ vm.applyThemes(listOf(t.themeFile), null) }, { Text("Aplicar") })
                    AssistChip({ imageFor = t; pickImage.launch(arrayOf("image/png", "image/jpeg", "image/*")) }, { Text("Trocar/adicionar imagem") })
                    AssistChip({ confirmRemove = t }, { Text("Remover", color = BfColors.Danger) })
                }
                if (t.images.isNotEmpty()) Text(t.images.joinToString { it.substringAfterLast('/') }, style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
            }
        }
        val active = st.themes.filter { it.active }
        if (st.themes.size > 1) OutlinedButton(onClick = { vm.applyThemes(st.themes.map { it.themeFile }, "boot_second") }, modifier = Modifier.fillMaxWidth()) {
            Text("Usar todos em sorteio a cada boot (${st.themes.size} temas)")
        }
        if (active.size > 1) Text("Vários temas ativos: o Ventoy sorteia um a cada boot.", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
    }

    zipUri?.let { uri ->
        TextInputDialog("Nome do tema", "Nome da pasta", "meu-tema", "Importar",
            help = "O .zip precisa conter um theme.txt (formato de tema do GRUB2). Será copiado para /ventoy/themes/<nome>.",
            onConfirm = { vm.importTheme(uri, it); zipUri = null }, onDismiss = { zipUri = null })
    }
    pendingImage?.let { (t, uri) ->
        TextInputDialog("Gravar imagem no tema", "Nome do arquivo no tema", "background.png", "Gravar",
            help = "Use o nome que o theme.txt espera (ex.: background.png) para SUBSTITUIR, ou um nome novo para adicionar. Formatos: PNG, JPG, TGA.",
            onConfirm = { vm.putThemeImage(t, uri, it); pendingImage = null }, onDismiss = { pendingImage = null })
    }
    confirmRemove?.let { t ->
        ConfirmDialog("Remover o tema ${t.name}?", "A pasta ${t.folder} será apagada do pendrive." + if (t.active) " O tema está em uso: o ventoy.json será ajustado." else "",
            "Remover", danger = true, onConfirm = { vm.removeTheme(t); confirmRemove = null }, onDismiss = { confirmRemove = null })
    }
    st.preview?.let { p ->
        AlertDialog(
            onDismissRequest = vm::closePreview,
            title = { Text("Pré-visualização (aproximada)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemePreviewCanvas(p)
                    Text("Desenho aproximado feito pelo app a partir do theme.txt; fontes e detalhes do GRUB podem variar no PC.",
                        style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
                }
            },
            confirmButton = { TextButton(onClick = vm::closePreview) { Text("Fechar") } },
            containerColor = BfColors.Surface,
        )
    }
}

/** Converte valores do GRUB (10%, 200, 30%+200, 95%-25) para pixels. */
internal fun grubDim(expr: String?, total: Float, default: Float): Float {
    if (expr.isNullOrBlank()) return default
    val m = Regex("""^\s*(-?\d+)(%?)\s*(?:([+-])\s*(\d+))?\s*$""").matchEntire(expr) ?: return default
    val base = m.groupValues[1].toFloat().let { if (m.groupValues[2] == "%") it * total / 100f else it }
    val off = m.groupValues[4].toFloatOrNull() ?: 0f
    return if (m.groupValues[3] == "-") base - off else base + off
}

internal fun grubColor(s: String?, default: Color): Color {
    if (s.isNullOrBlank()) return default
    val named = mapOf("white" to Color.White, "black" to Color.Black, "red" to Color.Red, "blue" to Color(0xFF0000FF),
        "green" to Color(0xFF00FF00), "yellow" to Color.Yellow, "cyan" to Color.Cyan, "magenta" to Color.Magenta, "gray" to Color.Gray)
    named[s.lowercase()]?.let { return it }
    val hex = s.removePrefix("#")
    return runCatching {
        when (hex.length) {
            6 -> Color(0xFF000000 or hex.toLong(16))
            8 -> Color(hex.toLong(16))
            else -> default
        }
    }.getOrDefault(default)
}

/** Desenha o menu do Ventoy numa tela virtual de 1024×768, escalada para caber no celular. */
@Composable
fun ThemePreviewCanvas(p: ThemePreview) {
    val measurer = rememberTextMeasurer()
    val bitmaps: Map<String, ImageBitmap> = remember(p) {
        p.images.mapNotNull { (k, v) -> runCatching { BitmapFactory.decodeByteArray(v, 0, v.size)?.asImageBitmap() }.getOrNull()?.let { k to it } }.toMap()
    }
    val t = p.theme
    val bm = t.find("boot_menu")?.properties.orEmpty()
    Canvas(Modifier.fillMaxWidth().aspectRatio(4f / 3f)) {
        val sx = size.width / 1024f
        val sy = size.height / 768f
        val bg = t.properties["desktop-image"]?.let { bitmaps[it] }
        drawRect(grubColor(t.properties["desktop-color"], Color.Black))
        bg?.let { drawImage(it, dstSize = IntSize(size.width.toInt(), size.height.toInt()), srcSize = IntSize(it.width, it.height), srcOffset = IntOffset.Zero) }
        // título
        t.properties["title-text"]?.takeIf { it.isNotBlank() }?.let { title ->
            drawText(measurer, title, Offset(size.width / 2 - title.length * 3.5f * sx, 40 * sy),
                TextStyle(color = grubColor(t.properties["title-color"], Color.White), fontSize = (14 * sx).sp))
        }
        // caixa do menu
        val left = grubDim(bm["left"], 1024f, 102f) * sx
        val top = grubDim(bm["top"], 768f, 230f) * sy
        val w = grubDim(bm["width"], 1024f, 820f) * sx
        val h = grubDim(bm["height"], 768f, 380f) * sy
        val menuImg = bm["menu_pixmap_style"]?.replace("*", "c")?.let { bitmaps[it] }
        if (menuImg != null) drawImage(menuImg, dstOffset = IntOffset(left.toInt(), top.toInt()), dstSize = IntSize(w.toInt(), h.toInt()), srcSize = IntSize(menuImg.width, menuImg.height), srcOffset = IntOffset.Zero)
        else drawRect(Color(0x66555555), Offset(left, top), Size(w, h))
        val itemH = (bm["item_height"]?.toFloatOrNull() ?: 30f) * sy
        val spacing = (bm["item_spacing"]?.toFloatOrNull() ?: 1f) * sy
        val sel = bm["selected_item_pixmap_style"]?.replace("*", "c")?.let { bitmaps[it] }
        p.items.forEachIndexed { i, name ->
            val y = top + 20 * sy + i * (itemH + spacing)
            if (y + itemH > top + h) return@forEachIndexed
            if (i == 0) {
                if (sel != null) drawImage(sel, dstOffset = IntOffset((left + 30 * sx).toInt(), y.toInt()), dstSize = IntSize((w - 60 * sx).toInt(), itemH.toInt()), srcSize = IntSize(sel.width, sel.height), srcOffset = IntOffset.Zero)
                else drawRect(Color(0x553C8DBC), Offset(left + 30 * sx, y), Size(w - 60 * sx, itemH))
            }
            val color = grubColor(if (i == 0) bm["selected_item_color"] else bm["item_color"], Color.White)
            drawText(measurer, name, Offset(left + 60 * sx, y + itemH * 0.2f), TextStyle(color = color, fontSize = (12 * sx).sp))
        }
        // rodapé com o texto do Ventoy (posição configurável no plugin theme)
        drawText(measurer, "1.1.18 BIOS   www.ventoy.net   F1:Help  F2:Browse  F3:TreeView", Offset(50 * sx, 730 * sy),
            TextStyle(color = Color(0xFF3A6BD8), fontSize = (10 * sx).sp))
    }
}
