package dev.bootforge.app.ui.screens.ventoy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bootforge.app.data.Format
import dev.bootforge.app.ui.VentoyViewModel
import dev.bootforge.app.ui.components.Badge
import dev.bootforge.app.ui.components.InfoRow
import dev.bootforge.app.ui.components.Notice
import dev.bootforge.app.ui.components.SectionCard
import dev.bootforge.app.ui.screens.Loading
import dev.bootforge.app.ui.theme.BfColors
import dev.bootforge.app.ui.theme.MonoStyle
import dev.bootforge.ventoy.config.ConfigIssue
import dev.bootforge.ventoy.config.VentoyConfig
import dev.bootforge.ventoy.config.VentoySchema
import dev.bootforge.ventoy.config.VentoySchema.FieldType
import dev.bootforge.ventoy.config.VentoySchema.Kind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Editor do /ventoy/ventoy.json: visual (por esquema oficial) e avançado (texto). */
@Composable
fun ConfigTab(vm: VentoyViewModel) {
    val st by vm.config.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(0) }
    var editOption by remember { mutableStateOf<VentoySchema.Field?>(null) }
    var editPlugin by remember { mutableStateOf<VentoySchema.Plugin?>(null) }
    LaunchedEffect(Unit) { if (!st.loaded) vm.loadConfig() }
    if (!st.loaded) { Loading("Lendo /ventoy/ventoy.json…"); return }

    val errors = st.issues.count { it.severity == ConfigIssue.Severity.ERROR }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("Visual", "Avançado (JSON)", "Backups").forEachIndexed { i, t ->
                SegmentedButton(mode == i, { mode = i }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(t) }
            }
        }
        // ---- status e salvar
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (errors > 0) Badge("$errors erro(s)", BfColors.Danger) else Badge("Sem erros", BfColors.Success)
                val warns = st.issues.size - errors
                if (warns > 0) Badge("$warns aviso(s)", BfColors.Warning)
                if (st.dirty) Badge("Não salvo", BfColors.Steel)
            }
            st.issues.take(12).forEach { i ->
                Text("${if (i.severity == ConfigIssue.Severity.ERROR) "✗" else "!"} ${i.where}${i.line?.let { " (linha $it, col. ${i.column})" } ?: ""}: ${i.message}",
                    style = MaterialTheme.typography.bodySmall, color = if (i.severity == ConfigIssue.Severity.ERROR) BfColors.Danger else BfColors.Warning)
            }
            Button(onClick = vm::saveConfig, enabled = st.dirty && st.issues.none { it.where == "sintaxe" }, modifier = Modifier.fillMaxWidth()) {
                Text("Salvar no pendrive (com backup automático)")
            }
            if (errors > 0) Text("Há erros: o Ventoy pode ignorar opções inválidas. Corrija antes de salvar, se possível.",
                style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
        }

        when (mode) {
            0 -> {
                val cfg = st.config
                if (cfg == null) {
                    Notice("O arquivo atual tem erro de sintaxe. Corrija no modo Avançado ou restaure um backup.", BfColors.Danger, Icons.Filled.Error)
                } else {
                    SectionCard(title = "Controle global (control)", icon = Icons.Filled.Tune) {
                        val values = cfg.control()
                        VentoySchema.CONTROL_OPTIONS.forEach { f ->
                            val v = values[f.key]
                            Column(Modifier.fillMaxWidth().clickable { editOption = f }.padding(vertical = 4.dp)) {
                                Text(f.label, style = MaterialTheme.typography.bodyMedium)
                                Text("${f.key} = ${v?.let { cur -> f.values.firstOrNull { it.first == cur }?.second ?: cur } ?: "padrão"}",
                                    style = MonoStyle, color = if (v == null) BfColors.TextDim else BfColors.Steel)
                            }
                        }
                    }
                    SectionCard(title = "Plugins", icon = Icons.Filled.Extension) {
                        Text("Toque para editar. Variantes por modo de boot (ex.: theme_uefi) podem ser editadas no modo Avançado.",
                            style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
                        VentoySchema.PLUGINS.filter { it.kind != Kind.CONTROL }.forEach { p ->
                            val present = cfg.plugin(p.key)
                            val count = when (present) { is JsonArray -> "${present.size} item(ns)"; is JsonObject -> "configurado"; null -> "não usado"; else -> "?" }
                            Column(Modifier.fillMaxWidth().clickable { editPlugin = p }.padding(vertical = 4.dp)) {
                                Text("${p.title} — $count", style = MaterialTheme.typography.bodyMedium)
                                Text(p.description, style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
                            }
                        }
                    }
                }
            }
            1 -> SectionCard(title = "ventoy.json", icon = Icons.Filled.Code) {
                OutlinedTextField(st.text, vm::editText, modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp), textStyle = MonoStyle)
                Text("Validação em tempo real. Arquivos com erro de SINTAXE não são gravados.", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
            }
            2 -> SectionCard(title = "Backups em /ventoy (${st.backups.size})", icon = Icons.Filled.History) {
                if (st.backups.isEmpty()) Text("Nenhum backup ainda. Cada gravação cria um automaticamente.", color = BfColors.TextDim)
                st.backups.forEach { b ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(b.name, style = MonoStyle)
                        Text("${Format.bytes(b.size)}${b.modifiedMillis?.let { " • " + Format.dateTime(it) } ?: ""}", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.restoreConfigBackup(b.name) }) { Text("Restaurar") }
                            TextButton(onClick = { vm.deleteConfigBackup(b.name) }) { Text("Excluir", color = BfColors.Danger) }
                        }
                    }
                }
            }
        }
    }

    val cfg = st.config
    editOption?.let { f ->
        if (cfg != null) ControlOptionDialog(f, cfg.control()[f.key], onSave = { v -> vm.editConfig(cfg.withControl(f.key, v)); editOption = null }, onDismiss = { editOption = null })
    }
    editPlugin?.let { p ->
        if (cfg != null) PluginEditorDialog(p, cfg.plugin(p.key), onSave = { v -> vm.editConfig(cfg.withPlugin(p.key, v)); editPlugin = null }, onDismiss = { editPlugin = null })
    }
}

@Composable
private fun ControlOptionDialog(f: VentoySchema.Field, current: String?, onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(current ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(f.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(f.key, style = MonoStyle, color = BfColors.Steel)
                if (f.help.isNotBlank()) Text(f.help, style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
                if (f.type == FieldType.ENUM) {
                    f.values.forEach { (v, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(value == v, { value = v }); Text("$label ($v)") }
                    }
                } else {
                    OutlinedTextField(value, { value = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (f.type == FieldType.INT) "Número" else if (f.type == FieldType.PATH) "Caminho (começa com /)" else "Valor") })
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(value.ifBlank { null }) }) { Text("Aplicar") } },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave(null) }) { Text("Usar padrão") }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        },
        containerColor = BfColors.Surface,
    )
}

/** Uma regra editável: alvo (image/dir/parent…) + campos. */
private class RuleModel(var targetKey: String, var targetValue: String, val fields: MutableMap<String, String>, val extra: Map<String, JsonElement>)

private fun text(e: JsonElement?): String = when (e) {
    is JsonPrimitive -> e.contentOrNull ?: ""
    is JsonArray -> e.joinToString("\n") { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }
    else -> ""
}

private fun element(f: VentoySchema.Field, s: String): JsonElement? {
    val t = s.trim()
    if (t.isEmpty()) return null
    return when (f.type) {
        FieldType.INT -> t.toIntOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(t)
        FieldType.PATH_LIST -> t.lines().map { it.trim() }.filter { it.isNotEmpty() }.let { if (it.size == 1) JsonPrimitive(it[0]) else JsonArray(it.map(::JsonPrimitive)) }
        else -> JsonPrimitive(t)
    }
}

private fun toRules(arr: JsonElement?, targets: List<VentoySchema.Field>, fields: List<VentoySchema.Field>): List<RuleModel> =
    (arr as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { o ->
        val t = targets.firstOrNull { o.containsKey(it.key) } ?: targets.first()
        RuleModel(t.key, text(o[t.key]), fields.associate { it.key to text(o[it.key]) }.toMutableMap(),
            o.filterKeys { k -> targets.none { it.key == k } && fields.none { it.key == k } })
    }

private fun fromRules(rules: List<RuleModel>, targets: List<VentoySchema.Field>, fields: List<VentoySchema.Field>): JsonArray =
    JsonArray(rules.map { r ->
        val m = LinkedHashMap<String, JsonElement>()
        val tf = targets.first { it.key == r.targetKey }
        element(tf, r.targetValue)?.let { m[r.targetKey] = it }
        fields.forEach { f -> element(f, r.fields[f.key] ?: "")?.let { m[f.key] = it } }
        m.putAll(r.extra) // campos que o app não conhece são preservados
        JsonObject(m)
    })

/** Editor genérico de um plugin, montado a partir do esquema oficial. */
@Composable
private fun PluginEditorDialog(p: VentoySchema.Plugin, value: JsonElement?, onSave: (JsonElement?) -> Unit, onDismiss: () -> Unit) {
    val obj = value as? JsonObject
    // campos de objeto (theme, password, menu_tip)
    val objectFields: List<VentoySchema.Field> = when (p.kind) {
        Kind.OBJECT, Kind.PASSWORD -> p.fields
        Kind.MENU_TIP -> listOf(VentoySchema.Field("left", "Posição (esquerda)", FieldType.STRING, "Ex.: 10%"),
            VentoySchema.Field("top", "Posição (topo)", FieldType.STRING, "Ex.: 81%"), VentoySchema.Field("color", "Cor", FieldType.STRING, "Ex.: blue ou #ffffff"))
        else -> emptyList()
    }
    val form = remember { mutableStateMapOf<String, String>().apply { objectFields.forEach { put(it.key, text(obj?.get(it.key))) } } }
    // regras (listas de objetos)
    val ruleTargets: List<VentoySchema.Field>
    val ruleFields: List<VentoySchema.Field>
    val ruleSource: JsonElement?
    when (p.kind) {
        Kind.RULES -> { ruleTargets = p.targets; ruleFields = p.fields; ruleSource = value }
        Kind.MENU_TIP -> { ruleTargets = p.targets; ruleFields = p.fields; ruleSource = obj?.get("tips") }
        Kind.PASSWORD -> { ruleTargets = p.targets; ruleFields = listOf(VentoySchema.Field("pwd", "Senha", FieldType.PASSWORD)); ruleSource = obj?.get("menupwd") }
        else -> { ruleTargets = emptyList(); ruleFields = emptyList(); ruleSource = null }
    }
    val rules = remember { mutableStateListOf<RuleModel>().apply { if (ruleTargets.isNotEmpty()) addAll(toRules(ruleSource, ruleTargets, ruleFields)) } }
    var listText by remember { mutableStateOf(text(value)) }
    var version by remember { mutableStateOf(0) } // força recomposição ao editar regras

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${p.title} (${p.key})") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(p.description, style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
                if (p.kind == Kind.STRING_LIST) {
                    OutlinedTextField(listText, { listText = it }, label = { Text("Um caminho por linha (ex.: /ISO/arquivo.iso)") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp), textStyle = MonoStyle)
                    Text("No Ventoy, * vale exatamente UM caractere.", style = MaterialTheme.typography.bodySmall, color = BfColors.TextDim)
                }
                objectFields.forEach { f -> FieldInput(f, form[f.key] ?: "") { form[f.key] = it } }
                if (ruleTargets.isNotEmpty()) {
                    Text(if (p.kind == Kind.PASSWORD) "Senhas por imagem (menupwd)" else "Regras", style = MaterialTheme.typography.titleMedium)
                    rules.forEachIndexed { i, r ->
                        key(version) {
                            SectionCard {
                                TargetSelector(ruleTargets, r.targetKey) { r.targetKey = it; version++ }
                                FieldInput(ruleTargets.first { it.key == r.targetKey }, r.targetValue) { r.targetValue = it; version++ }
                                ruleFields.forEach { f -> FieldInput(f, r.fields[f.key] ?: "") { r.fields[f.key] = it; version++ } }
                                TextButton(onClick = { rules.removeAt(i); version++ }) { Text("Remover regra", color = BfColors.Danger) }
                            }
                        }
                    }
                    OutlinedButton(onClick = { rules += RuleModel(ruleTargets.first().key, "", mutableMapOf(), emptyMap()); version++ }) { Text("Adicionar regra") }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val result: JsonElement? = when (p.kind) {
                    Kind.STRING_LIST -> listText.lines().map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { JsonArray(it.map(::JsonPrimitive)) }
                    Kind.RULES -> fromRules(rules, ruleTargets, ruleFields).takeIf { it.isNotEmpty() }
                    Kind.OBJECT, Kind.PASSWORD, Kind.MENU_TIP -> {
                        val m = LinkedHashMap<String, JsonElement>()
                        obj?.forEach { (k, v) -> if (objectFields.none { it.key == k } && k != "tips" && k != "menupwd") m[k] = v } // preserva desconhecidos
                        objectFields.forEach { f -> element(f, form[f.key] ?: "")?.let { m[f.key] = it } }
                        if (p.kind == Kind.MENU_TIP && rules.isNotEmpty()) m["tips"] = fromRules(rules, ruleTargets, ruleFields)
                        if (p.kind == Kind.PASSWORD && rules.isNotEmpty()) m["menupwd"] = fromRules(rules, ruleTargets, ruleFields)
                        m.takeIf { it.isNotEmpty() }?.let { JsonObject(it) }
                    }
                    Kind.CONTROL -> value
                }
                onSave(result)
            }) { Text("Aplicar") }
        },
        dismissButton = {
            Row {
                if (value != null) TextButton(onClick = { onSave(null) }) { Text("Remover plugin", color = BfColors.Danger) }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        },
        containerColor = BfColors.Surface,
    )
}

@Composable
private fun key(k: Int, content: @Composable () -> Unit) = androidx.compose.runtime.key(k) { content() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetSelector(targets: List<VentoySchema.Field>, current: String, onChange: (String) -> Unit) {
    if (targets.size == 1) return
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { open = it }) {
        OutlinedTextField(targets.first { it.key == current }.label + " (${current})", {}, readOnly = true, label = { Text("Aplicar a") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) }, modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable))
        ExposedDropdownMenu(open, { open = false }) {
            targets.forEach { t -> DropdownMenuItem({ Text("${t.label} (${t.key})") }, { onChange(t.key); open = false }) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldInput(f: VentoySchema.Field, value: String, onChange: (String) -> Unit) {
    if (f.type == FieldType.ENUM) {
        var open by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(open, { open = it }) {
            OutlinedTextField(f.values.firstOrNull { it.first == value }?.second ?: value.ifEmpty { "(padrão)" }, {}, readOnly = true,
                label = { Text(f.label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable))
            ExposedDropdownMenu(open, { open = false }) {
                DropdownMenuItem({ Text("(padrão)") }, { onChange(""); open = false })
                f.values.forEach { (v, l) -> DropdownMenuItem({ Text("$l ($v)") }, { onChange(v); open = false }) }
            }
        }
    } else {
        OutlinedTextField(value, onChange, label = { Text(f.label + if (f.required) " *" else "") }, modifier = Modifier.fillMaxWidth(),
            singleLine = f.type != FieldType.PATH_LIST, textStyle = if (f.type == FieldType.STRING) MaterialTheme.typography.bodyLarge else MonoStyle,
            supportingText = {
                val help = when (f.type) {
                    FieldType.PATH_LIST -> "Um caminho por linha"
                    FieldType.PASSWORD -> "txt#senha, md5#hash ou md5#sal#hash"
                    else -> f.help
                }
                if (help.isNotBlank()) Text(help)
            })
    }
}
