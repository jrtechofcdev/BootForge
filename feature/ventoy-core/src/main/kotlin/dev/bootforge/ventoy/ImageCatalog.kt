package dev.bootforge.ventoy

import dev.bootforge.fs.exfat.ExFatVolume
import dev.bootforge.ventoy.config.VentoyConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Uma imagem encontrada no pendrive e se ela aparece no menu do Ventoy. */
data class CatalogEntry(
    val path: String,
    val size: Long,
    /** Extensão em minúsculas: iso, wim, img, vhd, vhdx, efi, vtoy. */
    val type: String,
    val visible: Boolean,
    /** Motivo de NÃO aparecer no menu (null quando aparece). */
    val hiddenReason: String?,
    /** .efi só é listado quando o PC inicia em modo UEFI. */
    val uefiOnly: Boolean,
    val contiguous: Boolean,
    val modifiedMillis: Long?,
) {
    val name: String get() = path.substringAfterLast('/')
    val folder: String get() = path.substringBeforeLast('/').ifEmpty { "/" }
}

/**
 * Reproduz as regras com que o Ventoy 1.1.18 monta o menu de imagens
 * (`ventoy_collect_img_files` e `ventoy_plugin_get_image_list_index`, em `ventoy_cmd.c` /
 * `ventoy_plugin.c`). Assim o app mostra, ANTES do boot, o que vai aparecer no menu e por quê.
 */
object ImageCatalog {

    const val MIN_SIZE = 32768L // VTOY_FILT_MIN_FILE_SIZE
    val EXTENSIONS = setOf("iso", "wim", "img", "vhd", "vhdx", "efi", "vtoy")

    data class Rules(
        val searchRoot: String = "/",
        /** null = sem limite ("max"). */
        val maxLevel: Int? = null,
        val filterDotUnderscore: Boolean = false,
        val filterTrash: Boolean = true,
        val filteredTypes: Set<String> = emptySet(),
        val wimbootAvailable: Boolean = false,
        val vhdbootAvailable: Boolean = false,
        val whitelist: List<String>? = null,
        val blacklist: List<String>? = null,
    )

    /** Monta as regras a partir do `ventoy.json` (opções sem sufixo de arquitetura) e do pendrive. */
    fun rulesFrom(cfg: VentoyConfig?, vol: ExFatVolume): Rules {
        val c = cfg?.control().orEmpty()
        fun list(key: String) = (cfg?.plugin(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val flt = mapOf(
            "VTOY_FILE_FLT_ISO" to "iso", "VTOY_FILE_FLT_WIM" to "wim", "VTOY_FILE_FLT_EFI" to "efi",
            "VTOY_FILE_FLT_IMG" to "img", "VTOY_FILE_FLT_VHD" to "vhd", "VTOY_FILE_FLT_VTOY" to "vtoy",
        ).filterKeys { c[it] == "1" }.values.toMutableSet()
        if ("vhd" in flt) flt += "vhdx"
        return Rules(
            searchRoot = c["VTOY_DEFAULT_SEARCH_ROOT"]?.takeIf { it.startsWith("/") } ?: "/",
            maxLevel = c["VTOY_MAX_SEARCH_LEVEL"]?.toIntOrNull(),
            filterDotUnderscore = c["VTOY_FILT_DOT_UNDERSCORE_FILE"] == "1",
            filterTrash = c["VTOY_FILT_TRASH_DIR"] != "0",
            filteredTypes = flt,
            wimbootAvailable = vol.exists("/ventoy/ventoy_wimboot.img"),
            vhdbootAvailable = vol.exists("/ventoy/ventoy_vhdboot.img"),
            whitelist = list("image_list"),
            blacklist = list("image_blacklist"),
        )
    }

    /** Lista TODAS as imagens do pendrive (também as que não aparecem, com o motivo). */
    fun scan(vol: ExFatVolume, rules: Rules): List<CatalogEntry> {
        val out = mutableListOf<CatalogEntry>()
        val root = "/" + rules.searchRoot.trim('/')
        fun visit(dir: String, depth: Int, inheritedReason: String?) {
            val children = runCatching { vol.list(dir) }.getOrDefault(emptyList())
            val ignoreHere = children.any { !it.isDirectory && it.name == ".ventoyignore" }
            for (e in children) {
                val path = dir.trimEnd('/') + "/" + e.name
                if (e.isDirectory) {
                    var reason = inheritedReason
                    val childIgnored = runCatching { vol.list(path).any { !it.isDirectory && it.name == ".ventoyignore" } }.getOrDefault(false)
                    if (reason == null && childIgnored) reason = "a pasta ${path} contém .ventoyignore"
                    if (reason == null && rules.filterTrash && (e.name.startsWith(".trash-") || e.name == ".Trashes" || e.name.startsWith("\$RECYCLE.BIN"))) reason = "pasta de lixeira (VTOY_FILT_TRASH_DIR)"
                    if (reason == null && rules.filterDotUnderscore && e.name.startsWith("._")) reason = "nome começa com ._ (VTOY_FILT_DOT_UNDERSCORE_FILE)"
                    if (reason == null && !isInside(path, root) && !isInside(root, path)) reason = "fora da pasta de busca ${rules.searchRoot}"
                    if (reason == null && isInside(path, root) && rules.maxLevel != null && levelOf(path, root) > rules.maxLevel) reason = "além da profundidade máxima (VTOY_MAX_SEARCH_LEVEL = ${rules.maxLevel})"
                    if (reason == null && isInside(path, root) && rules.whitelist != null && rules.whitelist.none { dirMatches(it, "$path/") }) reason = "pasta fora da lista branca (image_list)"
                    visit(path, depth + 1, reason)
                    continue
                }
                val ext = e.name.substringAfterLast('.', "").lowercase()
                if (ext !in EXTENSIONS) continue
                val reason = inheritedReason ?: fileReason(e.name, ext, e.size, path, root, rules, ignoreHere && dir != root)
                out += CatalogEntry(path, e.size, ext, reason == null, reason, ext == "efi", e.contiguous || e.size == 0L, e.modifiedMillis)
            }
        }
        visit("/", 0, null)
        return out.sortedBy { it.path.lowercase() }
    }

    private fun fileReason(name: String, ext: String, size: Long, path: String, root: String, r: Rules, dirIgnored: Boolean): String? {
        if (dirIgnored) return "a pasta contém .ventoyignore"
        if (!isInside(path, root)) return "fora da pasta de busca ${r.searchRoot}"
        if (ext in r.filteredTypes) return "arquivos .$ext ocultados por VTOY_FILE_FLT_${if (ext == "vhdx") "VHD" else ext.uppercase()}"
        if (ext == "wim" && !r.wimbootAvailable) return ".wim exige o plugin wimboot (/ventoy/ventoy_wimboot.img)"
        if ((ext == "vhd" || ext == "vhdx") && !r.vhdbootAvailable) return ".vhd(x) exige o plugin vhdboot (/ventoy/ventoy_vhdboot.img)"
        if (ext == "img" && name.length == 18 && name.startsWith("ventoy_") &&
            (name.startsWith("ventoy_wimboot") || name.startsWith("ventoy_vhdboot"))
        ) return "arquivo interno do Ventoy"
        if (r.filterDotUnderscore && name.startsWith("._")) return "nome começa com ._ (VTOY_FILT_DOT_UNDERSCORE_FILE)"
        if (r.whitelist != null && r.whitelist.none { fileMatches(it, path) }) return "não está na lista branca (image_list)"
        if (r.blacklist != null && r.blacklist.any { fileMatches(it, path) }) return "está na lista negra (image_blacklist)"
        if (size < MIN_SIZE) return "menor que 32 KiB"
        return null
    }

    private fun isInside(path: String, root: String) = root == "/" || path == root || path.startsWith("$root/")

    /** Nível da pasta em relação à raiz de busca (pasta logo abaixo da raiz = 1). */
    private fun levelOf(dirPath: String, root: String): Int {
        val rel = if (root == "/") dirPath else dirPath.removePrefix(root)
        return rel.split('/').count { it.isNotEmpty() }
    }

    /** `ventoy_strncmp`: '*' casa exatamente UM caractere; compara n-1 caracteres e decide pelo n-ésimo. */
    internal fun ventoyStrncmp(pattern: String, str: String, n: Int): Int {
        if (n == 0) return 0
        var i = 0
        var left = n
        while (i < pattern.length && i < str.length && --left > 0) {
            if (pattern[i] != str[i] && pattern[i] != '*') break
            i++
        }
        val pc = if (i < pattern.length) pattern[i].code else 0
        val sc = if (i < str.length) str[i].code else 0
        return pc - sc
    }

    internal fun fileMatches(pattern: String, path: String) = path.length == pattern.length && ventoyStrncmp(pattern, path, path.length) == 0
    internal fun dirMatches(pattern: String, dirWithSlash: String) =
        dirWithSlash.length < pattern.length && ventoyStrncmp(pattern, dirWithSlash, dirWithSlash.length) == 0
}
