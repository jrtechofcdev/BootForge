package dev.bootforge.ventoy.config

import dev.bootforge.ventoy.config.VentoySchema.FieldType
import dev.bootforge.ventoy.config.VentoySchema.Kind
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Problema encontrado na validação. */
data class ConfigIssue(val severity: Severity, val where: String, val message: String, val line: Int? = null, val column: Int? = null) {
    enum class Severity { ERROR, WARNING }
}

/**
 * Conteúdo do `/ventoy/ventoy.json`, preservando a ordem das chaves.
 * Imutável: cada alteração devolve uma nova instância (facilita desfazer e comparar).
 */
class VentoyConfig(val root: JsonObject) {

    fun plugin(key: String): JsonElement? = root[key]

    /** Define (ou remove, com null) um plugin, mantendo a posição se já existir. */
    fun withPlugin(key: String, value: JsonElement?): VentoyConfig {
        val map = LinkedHashMap(root)
        if (value == null) map.remove(key) else map[key] = value
        return VentoyConfig(JsonObject(map))
    }

    // ------------------------------------------------------------ control

    /** Opções do `control` (lista de objetos de uma chave cada, como no Ventoy). */
    fun control(suffix: String? = null): Map<String, String> {
        val arr = root[key("control", suffix)] as? JsonArray ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        arr.forEach { item -> (item as? JsonObject)?.forEach { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { out[k] = it } } }
        return out
    }

    fun withControl(option: String, value: String?, suffix: String? = null): VentoyConfig {
        val current = LinkedHashMap(control(suffix))
        if (value == null) current.remove(option) else current[option] = value
        val arr = JsonArray(current.map { (k, v) -> JsonObject(mapOf(k to JsonPrimitive(v))) })
        return withPlugin(key("control", suffix), if (current.isEmpty()) null else arr)
    }

    // ------------------------------------------------------------ texto

    fun toJsonText(): String = PRETTY.encodeToString(JsonElement.serializer(), root) + "\n"

    /** Caminhos de arquivos referenciados (para conferir se existem no pendrive). */
    fun referencedPaths(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for ((key, value) in root) {
            val (plugin, _) = VentoySchema.parseKey(key) ?: continue
            fun addPaths(where: String, e: JsonElement?) {
                when (e) {
                    is JsonPrimitive -> e.contentOrNull?.let { out += where to it }
                    is JsonArray -> e.forEach { addPaths(where, it) }
                    else -> Unit
                }
            }
            when (plugin.kind) {
                Kind.OBJECT -> (value as? JsonObject)?.let { obj ->
                    plugin.fields.filter { it.type == FieldType.PATH || it.type == FieldType.PATH_LIST }.forEach { f ->
                        addPaths("$key.${f.key}", obj[f.key])
                    }
                }
                Kind.RULES -> (value as? JsonArray)?.forEachIndexed { i, rule ->
                    (rule as? JsonObject)?.let { obj ->
                        plugin.fields.filter { it.type == FieldType.PATH || it.type == FieldType.PATH_LIST }
                            .forEach { f -> addPaths("$key[${i + 1}].${f.key}", obj[f.key]) }
                    }
                }
                else -> Unit
            }
        }
        return out
    }

    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        private val PRETTY = Json { prettyPrint = true; prettyPrintIndent = "    " }
        private val STRICT = Json { isLenient = false }

        fun key(base: String, suffix: String?) = if (suffix == null) base else "${base}_$suffix"

        fun empty() = VentoyConfig(JsonObject(emptyMap()))

        /**
         * Lê o texto. Em caso de erro de sintaxe devolve o problema com linha e coluna.
         * O Ventoy só aceita UTF-8; um BOM UTF-8 inicial é tolerado pelo app mas sinalizado.
         */
        fun parse(text: String): Pair<VentoyConfig?, List<ConfigIssue>> {
            val issues = mutableListOf<ConfigIssue>()
            var t = text
            if (t.startsWith("﻿")) {
                issues += ConfigIssue(ConfigIssue.Severity.WARNING, "arquivo", "O arquivo começa com BOM; será salvo sem BOM.")
                t = t.substring(1)
            }
            val element = try {
                STRICT.parseToJsonElement(t)
            } catch (e: Exception) {
                val offset = Regex("offset (\\d+)").find(e.message ?: "")?.groupValues?.get(1)?.toIntOrNull()
                val (line, col) = offset?.let { lineCol(t, it) } ?: (null to null)
                val msg = (e.message ?: "").lineSequence().first().replace(Regex("^Unexpected JSON token at offset \\d+: "), "")
                issues += ConfigIssue(ConfigIssue.Severity.ERROR, "sintaxe", "JSON inválido: $msg", line, col)
                return null to issues
            }
            if (element !is JsonObject) {
                issues += ConfigIssue(ConfigIssue.Severity.ERROR, "sintaxe", "O ventoy.json precisa ser um objeto { … }")
                return null to issues
            }
            return VentoyConfig(element) to issues
        }

        private fun lineCol(text: String, offset: Int): Pair<Int, Int> {
            var line = 1
            var col = 1
            for (i in 0 until minOf(offset, text.length)) {
                if (text[i] == '\n') { line++; col = 1 } else col++
            }
            return line to col
        }
    }
}

/**
 * Validação semântica: plugins e campos conhecidos, tipos, valores aceitos e (opcionalmente)
 * existência dos arquivos referenciados no pendrive.
 */
object VentoyConfigValidator {

    fun validate(cfg: VentoyConfig, fileExists: ((String) -> Boolean)? = null): List<ConfigIssue> {
        val out = mutableListOf<ConfigIssue>()
        fun err(w: String, m: String) { out += ConfigIssue(ConfigIssue.Severity.ERROR, w, m) }
        fun warn(w: String, m: String) { out += ConfigIssue(ConfigIssue.Severity.WARNING, w, m) }

        for ((key, value) in cfg.root) validateEntry(key, value, ::err, ::warn)

        if (fileExists != null) {
            for ((where, path) in cfg.referencedPaths()) {
                if (path.contains('*')) continue
                val full = if (path.startsWith("/")) path else "/ventoy/$path"
                if (!fileExists(full)) warn(where, "Arquivo não encontrado no pendrive: $full")
            }
        }
        return out
    }

    private fun validateEntry(key: String, value: kotlinx.serialization.json.JsonElement, err: (String, String) -> Unit, warn: (String, String) -> Unit) {
        val parsed = VentoySchema.parseKey(key)
        if (parsed == null) { warn(key, "Plugin desconhecido na versão 1.1.18: será ignorado pelo Ventoy"); return }
        val plugin = parsed.first
        when (plugin.kind) {
            Kind.CONTROL -> {
                if (value !is JsonArray) { err(key, "Deve ser uma lista [ {\"VTOY_…\": \"valor\"}, … ]"); return }
                value.forEachIndexed { i, item ->
                    val obj = item as? JsonObject
                    if (obj == null || obj.size != 1) { err("$key[${i + 1}]", "Cada item deve ter exatamente uma opção"); return@forEachIndexed }
                    val (opt, v) = obj.entries.first()
                    val spec = VentoySchema.CONTROL_OPTIONS.firstOrNull { it.key == opt }
                    val sv = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
                    when {
                        spec == null -> warn("$key.$opt", "Opção desconhecida (será ignorada)")
                        sv == null -> err("$key.$opt", "O valor deve ser texto entre aspas")
                        spec.type == FieldType.ENUM && spec.values.none { it.first == sv } ->
                            err("$key.$opt", "Valor \"$sv\" inválido. Aceitos: ${spec.values.joinToString { it.first }}")
                        spec.type == FieldType.INT && sv.toIntOrNull()?.takeIf { it >= 0 } == null ->
                            err("$key.$opt", "Deve ser um número inteiro ≥ 0")
                        opt == "VTOY_MAX_SEARCH_LEVEL" && sv != "max" && sv.toIntOrNull()?.takeIf { it >= 0 } == null ->
                            err("$key.$opt", "Use \"max\" ou um número ≥ 0")
                        spec.type == FieldType.PATH && !sv.startsWith("/") -> err("$key.$opt", "O caminho deve começar com /")
                    }
                }
            }
            Kind.OBJECT -> {
                if (value !is JsonObject) { err(key, "Deve ser um objeto { … }"); return }
                checkFields(key, value, plugin.fields, err, warn)
            }
            Kind.RULES, Kind.MENU_TIP -> {
                val list = if (plugin.kind == Kind.MENU_TIP) {
                    if (value !is JsonObject) { err(key, "Deve ser um objeto com \"tips\": [ … ]"); return }
                    checkFields(key, JsonObject(value.filterKeys { it != "tips" }), listOf(
                        VentoySchema.Field("left", "", FieldType.STRING), VentoySchema.Field("top", "", FieldType.STRING),
                        VentoySchema.Field("color", "", FieldType.STRING),
                    ), err, warn)
                    value["tips"] as? JsonArray ?: run { err(key, "Falta a lista \"tips\""); return }
                } else value as? JsonArray ?: run { err(key, "Deve ser uma lista de regras [ { … } ]"); return }
                list.forEachIndexed { i, rule ->
                    val w = "$key[${i + 1}]"
                    val obj = rule as? JsonObject ?: run { err(w, "Cada regra deve ser um objeto"); return@forEachIndexed }
                    val targetsPresent = plugin.targets.filter { obj.containsKey(it.key) }
                    if (targetsPresent.isEmpty()) err(w, "Informe ${plugin.targets.joinToString(" ou ") { "\"${it.key}\"" }}")
                    if (targetsPresent.size > 1) warn(w, "Use apenas um entre ${targetsPresent.joinToString { it.key }}")
                    targetsPresent.forEach { t ->
                        if (t.type == FieldType.PATH) checkPath(w, t.key, obj[t.key], err)
                        else if ((obj[t.key] as? JsonPrimitive)?.isString != true) err("$w.${t.key}", "Deve ser texto entre aspas")
                    }
                    checkFields(w, JsonObject(obj.filterKeys { k -> plugin.targets.none { it.key == k } }), plugin.fields, err, warn)
                }
            }
            Kind.STRING_LIST -> {
                val arr = value as? JsonArray ?: run { err(key, "Deve ser uma lista de caminhos [ \"/…\" ]"); return }
                arr.forEachIndexed { i, e -> checkPath("$key[${i + 1}]", "caminho", e, err) }
            }
            Kind.PASSWORD -> {
                val obj = value as? JsonObject ?: run { err(key, "Deve ser um objeto { … }"); return }
                for ((k, v) in obj) {
                    when {
                        k == "menupwd" -> (v as? JsonArray)?.forEachIndexed { i, r ->
                            val o = r as? JsonObject
                            if (o == null || (!o.containsKey("file") && !o.containsKey("parent")) || !o.containsKey("pwd")) {
                                err("$key.menupwd[${i + 1}]", "Cada item precisa de \"file\" ou \"parent\" e \"pwd\"")
                            } else checkPassword("$key.menupwd[${i + 1}]", o["pwd"], err)
                        } ?: err("$key.menupwd", "Deve ser uma lista")
                        plugin.fields.any { it.key == k } -> checkPassword("$key.$k", v, err)
                        else -> warn("$key.$k", "Campo desconhecido (será ignorado)")
                    }
                }
            }
        }
    }

    private fun checkFields(
        where: String, obj: JsonObject, fields: List<VentoySchema.Field>,
        err: (String, String) -> Unit, warn: (String, String) -> Unit,
    ) {
        fields.filter { it.required && !obj.containsKey(it.key) }.forEach { err(where, "Campo obrigatório ausente: \"${it.key}\"") }
        for ((k, v) in obj) {
            val f = fields.firstOrNull { it.key == k }
            if (f == null) {
                warn("$where.$k", "Campo desconhecido (será ignorado)")
                continue
            }
            when (f.type) {
                FieldType.INT -> if ((v as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull == null) err("$where.$k", "Deve ser um número (sem aspas)")
                FieldType.ENUM -> {
                    val s = (v as? JsonPrimitive)?.contentOrNull
                    if (s == null || f.values.none { it.first == s }) err("$where.$k", "Valor inválido. Aceitos: ${f.values.joinToString { it.first }}")
                }
                FieldType.PATH -> checkPath(where, k, v, err, allowRelative = true)
                FieldType.PATH_LIST -> when (v) {
                    is JsonArray -> v.forEach { checkPath(where, k, it, err, allowRelative = true) }
                    else -> checkPath(where, k, v, err, allowRelative = true)
                }
                FieldType.STRING, FieldType.PASSWORD -> if ((v as? JsonPrimitive)?.isString != true) err("$where.$k", "Deve ser texto entre aspas")
            }
        }
    }

    private fun checkPath(where: String, key: String, v: JsonElement?, err: (String, String) -> Unit, allowRelative: Boolean = false) {
        val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (s == null || v is JsonNull) { err("$where.$key", "Deve ser um caminho entre aspas"); return }
        if (s.isEmpty()) err("$where.$key", "Caminho vazio")
        else if (!allowRelative && !s.startsWith("/")) err("$where.$key", "O caminho deve começar com / (raiz do pendrive)")
        else if (s.contains('\\')) err("$where.$key", "Use / e não \\ nos caminhos")
    }

    private fun checkPassword(where: String, v: JsonElement?, err: (String, String) -> Unit) {
        val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: run { err(where, "A senha deve ser texto"); return }
        val ok = when {
            s.startsWith("txt#") -> s.length > 4
            s.startsWith("md5#") -> {
                val parts = s.substring(4).split('#')
                parts.last().matches(Regex("[0-9a-fA-F]{32}")) && parts.size in 1..2
            }
            else -> false
        }
        if (!ok) err(where, "Formato inválido. Use txt#senha, md5#<32 hex> ou md5#sal#<32 hex>")
    }
}
