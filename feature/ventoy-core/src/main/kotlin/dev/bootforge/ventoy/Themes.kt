package dev.bootforge.ventoy

import dev.bootforge.fs.exfat.ExFatVolume
import dev.bootforge.ventoy.config.VentoyConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Tema do GRUB2 (`theme.txt`) — formato documentado no manual do GRUB ("Theme file format").
 * Propriedades globais `chave: valor` e componentes `+ tipo { chave = valor ... }` aninhados.
 * Usado para a pré-visualização aproximada no app.
 */
data class GrubTheme(val properties: Map<String, String>, val components: List<Component>) {
    data class Component(val type: String, val properties: Map<String, String>, val children: List<Component>)

    /** Primeiro componente do tipo (busca em profundidade). */
    fun find(type: String): Component? {
        fun walk(list: List<Component>): Component? {
            for (c in list) { if (c.type == type) return c; walk(c.children)?.let { return it } }
            return null
        }
        return walk(components)
    }

    companion object {
        fun parse(text: String): GrubTheme {
            val tokens = tokenize(text)
            var pos = 0
            fun value(): String = tokens.getOrElse(pos++) { "" }
            fun block(): Pair<Map<String, String>, List<Component>> {
                val props = LinkedHashMap<String, String>()
                val comps = mutableListOf<Component>()
                while (pos < tokens.size) {
                    val t = tokens[pos]
                    when {
                        t == "}" -> { pos++; return props to comps }
                        t == "+" -> {
                            pos++
                            val type = value()
                            if (tokens.getOrNull(pos) == "{") pos++
                            val (p, c) = block()
                            comps += Component(type, p, c)
                        }
                        else -> {
                            pos++
                            val sep = tokens.getOrNull(pos)
                            if (sep == ":" || sep == "=") { pos++; props[t] = value() }
                        }
                    }
                }
                return props to comps
            }
            val (props, comps) = block()
            return GrubTheme(props, comps)
        }

        /** Separa em palavras, strings entre aspas e símbolos { } : = +; ignora comentários (#). */
        private fun tokenize(text: String): List<String> {
            val out = mutableListOf<String>()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '#' -> { while (i < text.length && text[i] != '\n') i++ }
                    c.isWhitespace() -> i++
                    c == '"' -> {
                        val end = text.indexOf('"', i + 1).let { if (it < 0) text.length else it }
                        out += text.substring(i + 1, end); i = end + 1
                    }
                    c in "{}:=" -> { out += c.toString(); i++ }
                    // "+" no início de um token abre um componente ("+ boot_menu {"); dentro de
                    // valores como 30%+200 ele é lido junto com o valor pelo ramo abaixo.
                    c == '+' -> { out += "+"; i++ }
                    else -> {
                        val start = i
                        // valores como 95%-25 ou 30%+200 ficam inteiros
                        while (i < text.length && !text[i].isWhitespace() && text[i] !in "{}:=\"") i++
                        out += text.substring(start, i)
                    }
                }
            }
            return out
        }
    }
}

/** Um tema encontrado no pendrive. */
data class ThemeInfo(
    /** Caminho do theme.txt, ex.: /ventoy/themes/escuro/theme.txt */
    val themeFile: String,
    val name: String,
    val images: List<String>,
    /** Está referenciado pelo plugin "theme" do ventoy.json. */
    val active: Boolean,
) {
    val folder: String get() = themeFile.substringBeforeLast('/')
}

/**
 * Gerencia temas na partição de dados (o Ventoy procura temas a partir do `ventoy.json`,
 * plugin "theme"). Sem o plugin, o Ventoy usa o tema padrão que está na VTOYEFI.
 */
class ThemeManager(private val vol: ExFatVolume, private val store: VentoyConfigStore) {

    /** Procura pastas com theme.txt dentro de /ventoy. */
    fun discover(): List<ThemeInfo> {
        val active = activeThemeFiles()
        val out = mutableListOf<ThemeInfo>()
        if (!vol.exists("/ventoy")) return out
        vol.walk("/ventoy", maxDepth = 6) { path, e ->
            if (!e.isDirectory && e.name.equals("theme.txt", ignoreCase = true)) {
                val folder = path.substringBeforeLast('/')
                val images = runCatching { vol.list(folder) }.getOrDefault(emptyList())
                    .filter { !it.isDirectory && it.name.substringAfterLast('.').lowercase() in IMAGE_EXT }
                    .map { "$folder/${it.name}" }
                out += ThemeInfo(path, folder.substringAfterLast('/'), images, active.any { it.equals(path, ignoreCase = true) })
            }
        }
        return out.sortedBy { it.themeFile.lowercase() }
    }

    /** Caminhos de theme.txt usados no plugin "theme" (string ou lista; relativo = dentro de /ventoy). */
    fun activeThemeFiles(): List<String> {
        val cfg = store.load().first ?: return emptyList()
        val file = (cfg.plugin("theme") as? JsonObject)?.get("file") ?: return emptyList()
        val raw = when (file) {
            is JsonPrimitive -> listOfNotNull(file.contentOrNull)
            is JsonArray -> file.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            else -> emptyList()
        }
        return raw.map { if (it.startsWith("/")) it else "/ventoy/$it" }
    }

    /** Lê e interpreta um theme.txt (para a pré-visualização). */
    fun parse(themeFile: String): GrubTheme = GrubTheme.parse(vol.openFile(themeFile).use { String(it.readBytes(), Charsets.UTF_8) })

    fun readImage(path: String): ByteArray = vol.openFile(path).use { it.readBytes() }

    /**
     * Copia o tema ORIGINAL do pacote do Ventoy (`plugin/ventoy/theme`) para /ventoy/theme.
     * @return caminho do theme.txt instalado.
     */
    fun installOriginal(pkg: VentoyPackage, overwrite: Boolean = false): String {
        val files = pkg.listFiles("plugin/ventoy/theme")
        if (files.isEmpty()) throw IOException("O pacote do Ventoy não contém o tema original (plugin/ventoy/theme)")
        val dest = "/ventoy/theme"
        if (vol.exists(dest) && !overwrite) throw IOException("Já existe /ventoy/theme no pendrive")
        for (f in files) {
            val rel = f.removePrefix("plugin/ventoy/theme/")
            val target = "$dest/$rel"
            vol.mkdirs(target.substringBeforeLast('/'))
            val data = pkg.openFile(f).use { it.readBytes() }
            vol.writeFile(target, ByteArrayInputStream(data), data.size.toLong(), overwrite = true)
        }
        return "$dest/theme.txt"
    }

    /**
     * Importa um tema de um .zip (precisa conter um theme.txt). Os arquivos da pasta do
     * theme.txt são copiados para /ventoy/themes/[name]/.
     */
    fun importZip(input: InputStream, name: String, overwrite: Boolean = false): String {
        val safeName = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { throw IOException("Nome do tema vazio") }
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val n = e.name.replace('\\', '/').trimStart('/')
                if (n.split('/').any { it == ".." }) throw IOException("Arquivo .zip inseguro (caminho com ..)")
                val data = zip.readBytes()
                total += data.size
                if (total > MAX_THEME_BYTES) throw IOException("Tema maior que ${MAX_THEME_BYTES / 1048576} MiB")
                entries[n] = data
            }
        }
        val themeTxt = entries.keys.filter { it.substringAfterLast('/').equals("theme.txt", true) }.minByOrNull { it.count { c -> c == '/' } }
            ?: throw IOException("O .zip não contém theme.txt — não é um tema do GRUB/Ventoy")
        val base = themeTxt.substringBeforeLast('/', "")
        val dest = "/ventoy/themes/$safeName"
        if (vol.exists(dest)) {
            if (!overwrite) throw IOException("Já existe um tema chamado \"$safeName\"")
            vol.delete(dest, recursive = true)
        }
        for ((n, data) in entries) {
            if (base.isNotEmpty() && !n.startsWith("$base/")) continue
            val rel = if (base.isEmpty()) n else n.removePrefix("$base/")
            val target = "$dest/$rel"
            vol.mkdirs(target.substringBeforeLast('/'))
            vol.writeFile(target, ByteArrayInputStream(data), data.size.toLong(), overwrite = true)
        }
        return "$dest/${themeTxt.substringAfterLast('/')}"
    }

    /**
     * Aplica um ou mais temas no ventoy.json (mantendo as demais opções do plugin "theme").
     * Com vários temas, [defaultIndex] 0 = sorteio conforme [random].
     */
    fun apply(themeFiles: List<String>, displayGui: Boolean = true, defaultIndex: Int = 1, random: String? = null): String? {
        require(themeFiles.isNotEmpty())
        themeFiles.forEach { if (!vol.exists(it)) throw IOException("Arquivo não encontrado: $it") }
        val cfg = store.load().first ?: throw IOException("O ventoy.json atual tem erro de sintaxe; corrija antes de aplicar um tema")
        val current = (cfg.plugin("theme") as? JsonObject).orEmpty()
        val map = LinkedHashMap<String, JsonElement>(current)
        map["file"] = if (themeFiles.size == 1) JsonPrimitive(themeFiles[0]) else JsonArray(themeFiles.map { JsonPrimitive(it) })
        if (themeFiles.size > 1) {
            map["default_file"] = JsonPrimitive(defaultIndex)
            if (random != null) map["random"] = JsonPrimitive(random) else map.remove("random")
        } else { map.remove("default_file"); map.remove("random") }
        if (displayGui) map["display_mode"] = JsonPrimitive("GUI")
        return store.save(cfg.withPlugin("theme", JsonObject(map)))
    }

    /** Volta ao tema padrão do Ventoy (o da VTOYEFI): remove a opção "file" do plugin. */
    fun restoreDefault(): String? {
        val cfg = store.load().first ?: throw IOException("O ventoy.json atual tem erro de sintaxe")
        val current = cfg.plugin("theme") as? JsonObject ?: return null
        val rest = LinkedHashMap(current).apply { remove("file"); remove("default_file"); remove("random") }
        return store.save(cfg.withPlugin("theme", if (rest.isEmpty()) null else JsonObject(rest)))
    }

    /** Exclui a pasta de um tema; se estava em uso, retira do ventoy.json antes. */
    fun remove(theme: ThemeInfo) {
        val remaining = activeThemeFiles().filterNot { it.equals(theme.themeFile, ignoreCase = true) }
        if (theme.active) { if (remaining.isEmpty()) restoreDefault() else apply(remaining) }
        vol.delete(theme.folder, recursive = true)
    }

    /** Substitui ou adiciona uma imagem (PNG/JPG/TGA) na pasta do tema. */
    fun putImage(theme: ThemeInfo, fileName: String, input: InputStream, size: Long) {
        val ext = fileName.substringAfterLast('.').lowercase()
        if (ext !in IMAGE_EXT) throw IOException("O GRUB só aceita PNG, JPG/JPEG ou TGA em temas")
        vol.writeFile("${theme.folder}/$fileName", input, size, overwrite = true)
    }

    companion object {
        val IMAGE_EXT = setOf("png", "jpg", "jpeg", "tga")
        const val MAX_THEME_BYTES = 64L shl 20

        private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()
    }
}

/** Atalho usado pela interface. */
fun VentoyConfig.themeObject(): JsonObject? = plugin("theme") as? JsonObject
