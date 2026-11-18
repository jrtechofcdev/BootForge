package dev.bootforge.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.URI
import java.net.URLDecoder

/**
 * Descobre a versão mais recente de cada sistema consultando a FONTE OFICIAL, do jeito
 * documentado em docs/04-fontes-oficiais.md:
 *
 * | tipo                 | fonte                                   | checksum / assinatura |
 * |----------------------|-----------------------------------------|-----------------------|
 * | ubuntu-meta-release  | feed meta-release-lts (usado pelo próprio atualizador do Ubuntu) | SHA256SUMS + .gpg |
 * | directory-version    | índice de pastas (Linux Mint)           | sha256sum.txt + .gpg  |
 * | directory-file       | índice de arquivos (Debian)             | SHA512SUMS + .sign    |
 * | fedora-releases      | releases.json oficial                   | sha256 no JSON        |
 * | popos-api            | API de builds da System76               | sha_sum no JSON       |
 * | alpine-yaml          | latest-releases.yaml                    | sha256 no YAML        |
 * | html-regex           | páginas oficiais (Zorin)                | sha256 na página de ajuda |
 * | github-release       | API de releases do GitHub (Ventoy)      | sha256.txt da release |
 * | browser              | página oficial (Microsoft)              | — (informado pelo usuário) |
 */
class Resolvers(http: Http, private val keys: (String) -> OpenPgp.Keys) {

    /**
     * Cache por consulta: Ubuntu Desktop/Server e as edições do Mint usam o MESMO índice e o
     * mesmo arquivo de checksums. Pedir uma vez só é mais rápido e não sobrecarrega os
     * servidores oficiais (alguns espelhos limitam pedidos repetidos).
     */
    private val http: Http = object : Http {
        private val cache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
        override fun bytes(url: String, maxBytes: Int): ByteArray = cache[url] ?: http.bytes(url, maxBytes).also { cache[url] = it }
        override fun contentLength(url: String): Long? = http.contentLength(url)
    }

    fun resolve(s: SystemDef): ResolvedImage {
        val r = s.resolver
        return when (s.resolverType) {
            "browser" -> ResolvedImage(s, null, null, null, null, null, Verification.NONE, notes = s.notes)
            "ubuntu-meta-release" -> {
                val version = ubuntuVersion(http.text(r.need("feed")))
                signedFromDirectory(s, version, r.need("base").replace("{version}", version), r.need("file").replace("{version}", version))
            }
            "directory-version" -> {
                val idx = r.need("index")
                val pat = Regex(r.need("versionPattern"))
                val version = links(http.text(idx), idx).mapNotNull { pat.find(it.substringAfter(idx).ifEmpty { it })?.groupValues?.get(1) }
                    .maxWithOrNull(::compareVersions) ?: throw IOException("Nenhuma versão encontrada em $idx")
                signedFromDirectory(s, version, r.need("base").replace("{version}", version), r.need("file").replace("{version}", version))
            }
            "directory-file" -> {
                val idx = r.need("index")
                val pat = Regex(r.need("filePattern"))
                val best = links(http.text(idx), idx).map { it.substringAfterLast('/') }.mapNotNull { f -> pat.find(f)?.let { f to it.groupValues[1] } }
                    .maxWithOrNull { a, b -> compareVersions(a.second, b.second) } ?: throw IOException("Nenhum arquivo correspondente em $idx")
                signedFromDirectory(s, best.second, idx, best.first)
            }
            "fedora-releases" -> fedora(s)
            "popos-api" -> {
                val o = Json.parseToJsonElement(http.text(r.need("url"))).jsonObject
                val url = o.str("url") ?: throw IOException("API do Pop!_OS sem url")
                val ver = listOfNotNull(o.str("version"), o.str("build")?.let { "build $it" }).joinToString(" ")
                ResolvedImage(s, ver, https(url), url.substringAfterLast('/'), o.str("size")?.toLongOrNull(),
                    o.str("sha_sum")?.let { Checksum("SHA-256", hex(it, 64), r.need("url")) }, Verification.OFFICIAL_CHECKSUM, notes = s.notes)
            }
            "alpine-yaml" -> alpine(s)
            "html-regex" -> {
                val page = http.text(r.need("page"))
                val m = Regex(r.need("urlPattern")).find(page) ?: throw IOException("Link da ISO não encontrado em ${r.need("page")}")
                val url = m.value; val version = m.groupValues[1]
                val text = plainText(http.text(r.need("checksumPage")))
                val sum = Regex(r.need("checksumPattern").replace("{version}", Regex.escape(version))).find(text)?.groupValues?.get(1)
                    ?: throw IOException("Checksum oficial da versão $version não encontrado na página de verificação")
                ResolvedImage(s, version, https(url), url.substringAfterLast('/'), http.contentLength(url),
                    Checksum("SHA-256", hex(sum, 64), r.need("checksumPage")), Verification.OFFICIAL_CHECKSUM, notes = s.notes)
            }
            "github-release" -> github(s)
            else -> throw IOException("Resolvedor desconhecido: ${s.resolverType}")
        }
    }

    // ------------------------------------------------------------------ fontes

    /** Pasta com a ISO + arquivo de checksums assinado (Ubuntu, Debian, Mint). */
    private fun signedFromDirectory(s: SystemDef, version: String, base: String, file: String): ResolvedImage {
        val r = s.resolver
        val sumsUrl = base + r.need("checksums")
        val sums = http.bytes(sumsUrl)
        var verification = Verification.OFFICIAL_CHECKSUM
        var signedBy: String? = null
        val notes = s.notes.toMutableList()
        r["signature"]?.let { sigName ->
            val group = r["keys"] ?: throw IOException("${s.id}: assinatura sem grupo de chaves")
            val res = OpenPgp.verifyDetached(sums, http.bytes(base + sigName), keys(group))
            if (!res.valid) throw IOException("Verificação da assinatura oficial FALHOU: ${res.message}")
            verification = Verification.SIGNED; signedBy = res.fingerprint
            notes += "Checksums assinados por ${res.userId} (${res.fingerprint})"
        }
        val sum = ChecksumFile.find(String(sums, Charsets.UTF_8), file) ?: throw IOException("$file não aparece em ${r.need("checksums")}")
        val url = base + file
        return ResolvedImage(s, version, https(url), file, http.contentLength(url), sum.copy(source = sumsUrl), verification, signedBy, notes)
    }

    private fun fedora(s: SystemDef): ResolvedImage {
        val r = s.resolver
        val arr = Json.parseToJsonElement(http.text(r.need("feed"))).jsonArray
        val best = arr.map { it.jsonObject }.filter {
            it.str("variant") == r.need("variant") && it.str("subvariant") == r.need("subvariant") && it.str("arch") == r.need("arch") &&
                it.str("link")?.endsWith(".iso") == true && it.str("version")?.all { c -> c.isDigit() } == true // só versões finais (sem Beta/RC)
        }.maxWithOrNull { a, b -> compareVersions(a.str("version")!!, b.str("version")!!) } ?: throw IOException("Fedora: nenhuma versão final encontrada")
        val url = best.str("link")!!
        return ResolvedImage(s, best.str("version"), https(url), url.substringAfterLast('/'), best.str("size")?.toLongOrNull(),
            best.str("sha256")?.let { Checksum("SHA-256", hex(it, 64), r.need("feed")) }, Verification.OFFICIAL_CHECKSUM,
            notes = s.notes + "download.fedoraproject.org redireciona para um espelho oficial próximo; o checksum vem do releases.json oficial")
    }

    private fun alpine(s: SystemDef): ResolvedImage {
        val r = s.resolver
        val items = simpleYamlList(http.text(r.need("feed")))
        val it = items.firstOrNull { e -> e["flavor"] == r.need("flavor") } ?: throw IOException("Alpine: sabor ${r.need("flavor")} não encontrado")
        val url = r.need("base").replace("{branch}", it["branch"] ?: "latest-stable") + it.need("file")
        val sum = it["sha512"]?.let { v -> Checksum("SHA-512", hex(v, 128), r.need("feed")) } ?: it["sha256"]?.let { v -> Checksum("SHA-256", hex(v, 64), r.need("feed")) }
        return ResolvedImage(s, it["version"], https(url), it.need("file"), it["size"]?.toLongOrNull(), sum, Verification.OFFICIAL_CHECKSUM, notes = s.notes)
    }

    private fun github(s: SystemDef): ResolvedImage {
        val r = s.resolver
        val rel = Json.parseToJsonElement(http.text("https://api.github.com/repos/${r.need("repo")}/releases/latest")).jsonObject
        val assets = (rel["assets"] as? JsonArray ?: JsonArray(emptyList())).map { it.jsonObject }
        val pat = Regex(r.need("assetPattern"))
        val asset = assets.firstOrNull { pat.matches(it.str("name") ?: "") } ?: throw IOException("Release sem o arquivo esperado")
        val name = asset.str("name")!!
        val url = asset.str("browser_download_url")!!
        val sum = r["checksums"]?.let { cs ->
            assets.firstOrNull { it.str("name") == cs }?.str("browser_download_url")?.let { csUrl ->
                ChecksumFile.find(http.text(csUrl), name)?.copy(source = csUrl)
            }
        }
        return ResolvedImage(s, pat.find(name)?.groupValues?.get(1) ?: rel.str("tag_name"), https(url), name, asset.str("size")?.toLongOrNull(), sum,
            if (sum != null) Verification.OFFICIAL_CHECKSUM else Verification.NONE, notes = s.notes)
    }

    // ------------------------------------------------------------------ utilidades

    companion object {
        val TYPES = setOf("browser", "ubuntu-meta-release", "directory-version", "directory-file", "fedora-releases", "popos-api", "alpine-yaml", "html-regex", "github-release")

        /** Última versão LTS ainda suportada no feed meta-release do Ubuntu (ex.: "24.04.3"). */
        fun ubuntuVersion(feed: String): String {
            val blocks = feed.split(Regex("\\n\\s*\\n")).map { b -> b.lines().mapNotNull { l -> l.split(": ", limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap() }
            return blocks.filter { it["Supported"] == "1" && it["Version"] != null }.map { it["Version"]!!.removeSuffix(" LTS").trim() }
                .maxWithOrNull(::compareVersions) ?: throw IOException("Feed do Ubuntu sem versão suportada")
        }

        /** Compara versões numericamente, parte por parte ("22.10" > "22.9"; "13.10.0" > "13.7.0"). */
        fun compareVersions(a: String, b: String): Int {
            val x = Regex("\\d+").findAll(a).map { it.value.toLong() }.toList()
            val y = Regex("\\d+").findAll(b).map { it.value.toLong() }.toList()
            for (i in 0 until maxOf(x.size, y.size)) {
                val c = (x.getOrElse(i) { -1 }).compareTo(y.getOrElse(i) { -1 })
                if (c != 0) return c
            }
            return 0
        }

        /** Links (href) de uma página de índice, já resolvidos para URLs absolutas. */
        fun links(html: String, base: String): List<String> =
            Regex("href=\"([^\"#?]+)\"").findAll(html).map { m ->
                val href = URLDecoder.decode(m.groupValues[1], "UTF-8")
                runCatching { URI(base).resolve(m.groupValues[1]).toString() }.getOrDefault(href)
            }.distinct().toList()

        /** Texto de uma página HTML (sem tags, entidades básicas decodificadas, espaços normalizados). */
        fun plainText(html: String): String = html
            .replace(Regex("<script[\\s\\S]*?</script>|<style[\\s\\S]*?</style>"), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            .replace(Regex("\\s+"), " ")

        /** Leitor simples do formato do latest-releases.yaml do Alpine (lista de mapas). */
        fun simpleYamlList(yaml: String): List<Map<String, String>> {
            val out = mutableListOf<MutableMap<String, String>>()
            for (line in yaml.lines()) {
                if (line.trim() == "-") { out += mutableMapOf(); continue }
                val m = Regex("^\\s{2}([a-z0-9_]+):\\s*(.*)$").find(line) ?: continue
                out.lastOrNull()?.put(m.groupValues[1], m.groupValues[2].trim().trim('"'))
            }
            return out
        }

        private fun https(url: String): String {
            if (!url.startsWith("https://")) throw IOException("Link sem HTTPS recusado: $url")
            return url
        }

        private fun hex(s: String, len: Int): String {
            val h = s.trim().lowercase()
            if (h.length != len || !h.all { it in "0123456789abcdef" }) throw IOException("Checksum com formato inválido")
            return h
        }

        private fun JsonObject.str(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
        private fun Map<String, String>.need(k: String) = this[k] ?: throw IOException("Parâmetro \"$k\" ausente")
    }
}

/** Leitura de arquivos de checksum nos formatos GNU ("hash *arquivo") e BSD ("SHA256 (arquivo) = hash"). */
object ChecksumFile {
    fun find(text: String, fileName: String): Checksum? {
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            Regex("^(SHA256|SHA512) \\((.+)\\) = ([0-9a-fA-F]+)$").find(line)?.let { m ->
                if (m.groupValues[2] == fileName) return of(m.groupValues[3])
            }
            Regex("^([0-9a-fA-F]{64}|[0-9a-fA-F]{128})\\s+\\*?(.+)$").find(line)?.let { m ->
                if (m.groupValues[2].trim().removePrefix("./") == fileName) return of(m.groupValues[1])
            }
        }
        return null
    }

    private fun of(hex: String) = Checksum(if (hex.length == 128) "SHA-512" else "SHA-256", hex.lowercase(), "")
}
