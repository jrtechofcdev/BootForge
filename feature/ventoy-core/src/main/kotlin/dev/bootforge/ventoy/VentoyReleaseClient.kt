package dev.bootforge.ventoy

import dev.bootforge.blockdev.ProgressListener
import dev.bootforge.blockdev.ProgressTracker
import dev.bootforge.integrity.CancellationSignal
import dev.bootforge.integrity.ChecksumFile
import dev.bootforge.integrity.HashAlgorithm
import dev.bootforge.integrity.Hashing
import dev.bootforge.integrity.toHex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Consulta e baixa versões do Ventoy SOMENTE da fonte oficial: as releases do repositório
 * github.com/ventoy/Ventoy (API pública do GitHub).
 *
 * Segurança:
 *  - o pacote `ventoy-X-linux.tar.gz` é conferido contra o `sha256.txt` publicado NA MESMA release;
 *  - sem `sha256.txt`, o download é marcado como "autenticidade não verificada" e a interface
 *    exige confirmação extra;
 *  - dos arquivos extraídos é registrado um manifesto com SHA-256 (detecta alteração posterior).
 */
class VentoyReleaseClient(
    private val apiBase: String = "https://api.github.com/repos/ventoy/Ventoy",
    private val userAgent: String = "BootForge",
    /** Somente para testes automatizados com servidor local. No app é sempre false (só HTTPS). */
    private val allowHttp: Boolean = false,
) {

    data class Asset(val name: String, val url: String, val size: Long)

    data class Release(
        val tag: String,
        val version: String,
        val name: String,
        val publishedAt: String,
        val prerelease: Boolean,
        val linuxPackage: Asset?,
        val checksums: Asset?,
    )

    /** Resultado do download: o pacote pronto para instalar/atualizar e se foi verificado. */
    data class Downloaded(val pkg: VentoyPackage, val version: String, val verified: Boolean, val archiveSha256: String, val dir: File)

    private val json = Json { ignoreUnknownKeys = true }

    fun listReleases(limit: Int = 10): List<Release> {
        val text = get("$apiBase/releases?per_page=$limit").use { String(it.readBytes(), Charsets.UTF_8) }
        val arr = json.parseToJsonElement(text) as? JsonArray ?: throw IOException("Resposta inesperada da API do GitHub")
        return arr.mapNotNull { it as? JsonObject }.map { r ->
            val tag = r.str("tag_name") ?: ""
            val version = tag.removePrefix("v")
            val assets = (r["assets"] as? JsonArray).orEmpty().mapNotNull { a ->
                (a as? JsonObject)?.let { Asset(it.str("name") ?: "", it.str("browser_download_url") ?: "", (it["size"] as? JsonPrimitive)?.longOrNull ?: -1) }
            }
            Release(
                tag = tag, version = version, name = r.str("name") ?: tag,
                publishedAt = r.str("published_at") ?: "",
                prerelease = (r["prerelease"] as? JsonPrimitive)?.booleanOrNull ?: false,
                linuxPackage = assets.firstOrNull { it.name == "ventoy-$version-linux.tar.gz" },
                checksums = assets.firstOrNull { it.name.equals("sha256.txt", ignoreCase = true) },
            )
        }
    }

    fun latestStable(): Release? = listReleases().firstOrNull { !it.prerelease && it.linuxPackage != null }

    /**
     * Baixa (com retomada) e prepara o pacote em [destRoot]/ventoy-X/.
     * Etapas reais de progresso: download → conferência → extração.
     */
    fun download(
        release: Release, destRoot: File,
        listener: ProgressListener = ProgressListener.NONE, cancel: CancellationSignal = CancellationSignal.NONE,
    ): Downloaded {
        val asset = release.linuxPackage ?: throw IOException("A release ${release.tag} não tem o pacote Linux")
        val dir = File(destRoot, "ventoy-${release.version}").apply { mkdirs() }
        val archive = File(dir, asset.name)
        val part = File(dir, asset.name + ".part")
        if (!archive.exists()) {
            downloadResumable(asset.url, part, asset.size, listener, cancel)
            if (!part.renameTo(archive)) throw IOException("Não foi possível finalizar o download")
        }
        val sha = Hashing.hash(archive)
        var verified = false
        if (release.checksums != null) {
            val text = get(release.checksums.url).use { String(it.readBytes(), Charsets.UTF_8) }
            val entry = ChecksumFile.find(ChecksumFile.parse(text), asset.name)
                ?: throw IOException("sha256.txt da release não lista ${asset.name}")
            if (!Hashing.matches(entry.hash, sha)) {
                archive.delete()
                throw IOException("SHA-256 do download NÃO confere com o publicado pelo Ventoy. Arquivo descartado.")
            }
            verified = true
        }
        val manifest = extract(archive, File(dir, "pkg"))
        val pkgDir = File(dir, "pkg")
        val version = File(pkgDir, VentoyPackage.VERSION).readText().trim()
        if (version != release.version) throw IOException("Versão do pacote ($version) difere da release (${release.version})")
        File(dir, "manifest.txt").writeText(
            buildString {
                appendLine("version=$version"); appendLine("archive_sha256=$sha"); appendLine("verified=$verified")
                manifest.forEach { (k, v) -> appendLine("file=$k=$v") }
            },
        )
        return Downloaded(VentoyPackage.fromDirectory(pkgDir, manifest, origin(verified)), version, verified, sha, dir)
    }

    private fun origin(verified: Boolean) =
        if (verified) "baixado do GitHub oficial (SHA-256 conferido)" else "baixado do GitHub oficial (SEM checksum oficial)"

    /** Pacotes já baixados anteriormente (reaproveitados sem novo download). */
    fun downloaded(destRoot: File): List<Downloaded> = destRoot.listFiles().orEmpty().mapNotNull { dir ->
        val m = File(dir, "manifest.txt").takeIf { it.isFile } ?: return@mapNotNull null
        val lines = m.readLines()
        val hashes = lines.filter { it.startsWith("file=") }.associate { l -> l.removePrefix("file=").let { it.substringBeforeLast('=') to it.substringAfterLast('=') } }
        val verified = lines.any { it == "verified=true" }
        val version = lines.firstOrNull { it.startsWith("version=") }?.substringAfter('=') ?: return@mapNotNull null
        Downloaded(VentoyPackage.fromDirectory(File(dir, "pkg"), hashes, origin(verified)), version, verified,
            lines.firstOrNull { it.startsWith("archive_sha256=") }?.substringAfter('=') ?: "", dir)
    }

    // ------------------------------------------------------------ HTTP

    private fun open(url: String, offset: Long = 0): HttpURLConnection {
        if (!url.startsWith("https://") && !(allowHttp && url.startsWith("http://"))) throw IOException("Somente HTTPS é aceito: $url")
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", userAgent)
        c.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
        if (offset > 0) c.setRequestProperty("Range", "bytes=$offset-")
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.instanceFollowRedirects = true
        return c
    }

    private fun get(url: String): InputStream {
        val c = open(url)
        if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode} ao acessar $url")
        return c.inputStream
    }

    private fun downloadResumable(url: String, part: File, expected: Long, listener: ProgressListener, cancel: CancellationSignal) {
        val offset = if (part.exists()) part.length() else 0L
        val c = open(url, offset)
        val code = c.responseCode
        val append = code == 206 && offset > 0
        if (code !in 200..299) throw IOException("HTTP $code ao baixar $url")
        val total = if (expected > 0) expected else (c.contentLengthLong.takeIf { it > 0 }?.plus(if (append) offset else 0) ?: -1)
        val tracker = ProgressTracker("Baixando", total, listener)
        var done = if (append) offset else 0L
        c.inputStream.use { input ->
            FileOutputStream(part, append).use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    cancel.throwIfCancelled()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    tracker.update(done)
                }
            }
        }
        tracker.update(done, force = true)
        if (expected > 0 && done != expected) throw IOException("Download incompleto ($done de $expected bytes). Tente novamente para retomar.")
    }

    // ------------------------------------------------------------ tar.gz

    /** Extrai só o necessário (boot, ventoy/, plugin/) removendo a pasta raiz do pacote. Retorna hashes. */
    private fun extract(archive: File, dest: File): Map<String, String> {
        dest.deleteRecursively(); dest.mkdirs()
        val hashes = LinkedHashMap<String, String>()
        GZIPInputStream(archive.inputStream().buffered()).use { input ->
            val header = ByteArray(512)
            var longName: String? = null
            while (true) {
                if (!readFully(input, header)) break
                if (header.all { it.toInt() == 0 }) break
                var name = longName ?: cstr(header, 0, 100).let { n -> cstr(header, 345, 155).let { p -> if (p.isNotEmpty()) "$p/$n" else n } }
                longName = null
                val size = cstr(header, 124, 12).trim().ifEmpty { "0" }.toLong(8)
                val type = header[156].toInt().toChar()
                val data = if (size > 0) readBytes(input, size) else ByteArray(0)
                skipPadding(input, size)
                if (type == 'L') { longName = String(data, Charsets.UTF_8).trimEnd('\u0000'); continue }
                if (type != '0' && type != '\u0000') continue
                name = name.substringAfter('/') // remove "ventoy-1.1.18/"
                if (name.split('/').any { it == ".." }) throw IOException("Pacote com caminho inseguro")
                val wanted = name in setOf(VentoyPackage.BOOT_IMG, VentoyPackage.CORE_IMG_XZ, VentoyPackage.DISK_IMG_XZ, VentoyPackage.VERSION) ||
                    name.startsWith("plugin/")
                if (!wanted) continue
                val out = File(dest, name).apply { parentFile.mkdirs() }
                out.writeBytes(data)
                if (!name.startsWith("plugin/")) hashes[name] = HashAlgorithm.SHA256.newDigest().digest(data).toHex()
            }
        }
        listOf(VentoyPackage.BOOT_IMG, VentoyPackage.CORE_IMG_XZ, VentoyPackage.DISK_IMG_XZ, VentoyPackage.VERSION).forEach {
            if (!File(dest, it).isFile) throw IOException("Pacote incompleto: falta $it")
        }
        return hashes.filterKeys { it != VentoyPackage.VERSION }
    }

    private fun readFully(input: InputStream, b: ByteArray): Boolean {
        var n = 0
        while (n < b.size) {
            val r = input.read(b, n, b.size - n)
            if (r < 0) { if (n == 0) return false else throw IOException("tar truncado") }
            n += r
        }
        return true
    }

    private fun readBytes(input: InputStream, size: Long): ByteArray {
        if (size > 256L shl 20) throw IOException("Arquivo grande demais no pacote")
        val b = ByteArray(size.toInt())
        var n = 0
        while (n < b.size) { val r = input.read(b, n, b.size - n); if (r < 0) throw IOException("tar truncado"); n += r }
        return b
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val pad = ((512 - size % 512) % 512).toInt()
        if (pad > 0) readBytes(input, pad.toLong())
    }

    private fun cstr(b: ByteArray, off: Int, len: Int) = String(b, off, len, Charsets.UTF_8).substringBefore('\u0000')

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
}
