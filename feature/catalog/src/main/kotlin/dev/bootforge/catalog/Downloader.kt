package dev.bootforge.catalog

import dev.bootforge.blockdev.Progress
import dev.bootforge.blockdev.ProgressListener
import dev.bootforge.integrity.CancellationSignal
import dev.bootforge.integrity.HashAlgorithm
import dev.bootforge.integrity.OperationCancelledException
import dev.bootforge.integrity.toHex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** O que baixar (normalmente vem de um [ResolvedImage]). */
data class DownloadRequest(
    val url: String,
    val fileName: String,
    val title: String,
    val version: String? = null,
    val systemId: String? = null,
    val expectedSize: Long? = null,
    val checksum: Checksum? = null,
    val verification: Verification = Verification.NONE,
    val signedBy: String? = null,
) {
    companion object {
        fun of(r: ResolvedImage) = DownloadRequest(
            r.downloadUrl ?: throw IOException("Este sistema só pode ser baixado pelo navegador"),
            r.fileName ?: r.downloadUrl.substringAfterLast('/'), r.system.name, r.version, r.system.id,
            r.sizeBytes, r.checksum, r.verification, r.signedBy,
        )
    }
}

/** Pausar (mantém o .part para continuar depois) ou cancelar (apaga). */
class DownloadControl : CancellationSignal() {
    @Volatile var paused = false; private set

    /** Sinal que só dispara ao CANCELAR (não ao pausar) — usado na verificação final. */
    val hardCancel = CancellationSignal()

    fun pause() { paused = true; super.cancel() }
    override fun cancel() { hardCancel.cancel(); super.cancel() }
}

/** Download pausado: o .part fica guardado para continuar depois. */
class DownloadPausedException(val bytes: Long) : IOException("Download pausado")

/**
 * Downloads grandes (ISOs de vários GB) com:
 *  - retomada (HTTP Range + If-Range com ETag/Last-Modified), inclusive depois de fechar o app;
 *  - retomada AUTOMÁTICA quando a conexão cai (até [autoRetries] vezes);
 *  - pausa e cancelamento;
 *  - progresso real (bytes, velocidade, tempo restante);
 *  - verificação no fim (SHA-256/512 oficial) antes de entrar na biblioteca.
 *
 * Arquivos: "<nome>.part" (dados) e "<nome>.part.json" (de onde veio e como retomar).
 */
class Downloader(private val dir: File, private val http: UrlConnectionHttp = UrlConnectionHttp(), private val autoRetries: Int = 5) {

    init { dir.mkdirs() }

    fun partFile(fileName: String) = File(dir, "$fileName.part")
    private fun metaFile(fileName: String) = File(dir, "$fileName.part.json")

    /** Bytes já baixados de um download pausado/interrompido (0 se não houver). */
    fun partialBytes(req: DownloadRequest): Long = partFile(req.fileName).takeIf { it.exists() && readMeta(req.fileName)?.get("url") == req.url }?.length() ?: 0L

    /**
     * Baixa (ou continua) e verifica. Devolve o item já registrado na [library].
     * @throws DownloadPausedException se pausado; OperationCancelledException se cancelado.
     */
    fun download(req: DownloadRequest, library: Library, listener: ProgressListener, control: DownloadControl): LibraryItem {
        checkName(req.fileName)
        val part = partFile(req.fileName)
        var meta = readMeta(req.fileName)
        if (part.exists() && meta?.get("url") != req.url) { part.delete(); meta = null }
        var failures = 0
        var restartedOnce = false
        val started = System.nanoTime()
        val startBytes = if (part.exists()) part.length() else 0L
        while (true) {
            try {
                val total = transfer(req, part, meta, listener, control, started, startBytes)
                if (part.length() != total) throw IOException("Download incompleto (${part.length()} de $total bytes)")
                break
            } catch (e: RestartException) {
                if (restartedOnce) throw IOException("O servidor não permite continuar este download: ${e.message}")
                restartedOnce = true
                part.delete(); metaFile(req.fileName).delete(); meta = null
            } catch (e: OperationCancelledException) {
                if (control.paused) throw DownloadPausedException(part.length())
                part.delete(); metaFile(req.fileName).delete()
                throw e
            } catch (e: HttpException) {
                if (e.code < 500 || ++failures > autoRetries) throw e
                Thread.sleep(minOf(30_000L, 2000L shl failures))
                meta = readMeta(req.fileName)
            } catch (e: IOException) {
                // queda de conexão: tenta continuar de onde parou
                if (++failures > autoRetries) throw IOException("Download interrompido (${e.message}). Ele pode ser retomado depois.", e)
                Thread.sleep(minOf(30_000L, 2000L shl failures))
                meta = readMeta(req.fileName)
            }
        }

        // concluído: move para o nome final e verifica
        val target = File(dir, req.fileName)
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw IOException("Não foi possível finalizar o arquivo")
        metaFile(req.fileName).delete()
        // a verificação não é "pausável": pausar aqui não pode deixar um arquivo completo perdido
        return try {
            library.verifyAndAdd(target, req, listener, control.hardCancel)
        } catch (e: OperationCancelledException) {
            target.delete() // cancelado durante a verificação: descarta
            throw e
        }
    }

    /** Uma tentativa de transferência. Devolve o tamanho total do arquivo. */
    private fun transfer(
        req: DownloadRequest, part: File, meta: Map<String, String>?, listener: ProgressListener,
        control: DownloadControl, started: Long, startBytes: Long,
    ): Long {
        val offset = if (part.exists()) part.length() else 0L
        val headers = mutableMapOf<String, String>()
        val sameServer = meta?.get("finalUrl")
        val url = sameServer ?: req.url
        if (offset > 0) {
            headers["Range"] = "bytes=$offset-"
            (meta?.get("etag") ?: meta?.get("lastModified"))?.let { headers["If-Range"] = it }
        }
        val c = http.open(url, "GET", headers)
        try {
            val code = c.responseCode
            val total: Long
            when {
                code == 206 -> {
                    val cr = c.getHeaderField("Content-Range") ?: throw RestartException("resposta parcial sem Content-Range")
                    val m = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").find(cr) ?: throw RestartException("Content-Range inválido")
                    if (m.groupValues[1].toLong() != offset) throw RestartException("o servidor continuou de outro ponto")
                    total = m.groupValues[3].toLongOrNull() ?: (offset + c.contentLengthLong)
                }
                code == 200 -> {
                    if (offset > 0) throw RestartException("o arquivo no servidor mudou ou não aceita continuar")
                    total = c.contentLengthLong
                }
                code == 416 && offset > 0 -> {
                    val size = meta?.get("total")?.toLongOrNull()
                    if (size == offset) return offset
                    throw RestartException("posição inválida")
                }
                else -> throw HttpException(code, url)
            }
            if (total <= 0) throw IOException("O servidor não informou o tamanho do arquivo")
            req.expectedSize?.let { if (it != total) throw IOException("Tamanho no servidor ($total) diferente do oficial ($it): download recusado") }
            writeMeta(req.fileName, req.url, c.url.toString(), c.getHeaderField("ETag"), c.getHeaderField("Last-Modified"), total)

            val buf = ByteArray(256 * 1024)
            var done = offset
            var lastEmit = 0L
            FileOutputStream(part, offset > 0).use { out ->
                c.inputStream.use { input ->
                    while (true) {
                        control.throwIfCancelled()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.nanoTime()
                        if (now - lastEmit > 300_000_000L) {
                            lastEmit = now
                            val secs = (now - started) / 1e9
                            listener.onProgress(Progress("Baixando", done, total, if (secs > 0) (done - startBytes) / secs else 0.0))
                        }
                    }
                }
            }
            if (done < total) throw IOException("conexão encerrada antes do fim ($done de $total)")
            return total
        } finally {
            c.disconnect()
        }
    }

    private class RestartException(msg: String) : IOException(msg)

    private fun writeMeta(name: String, url: String, finalUrl: String, etag: String?, lastModified: String?, total: Long) {
        val o = buildJsonObject {
            put("url", url); put("finalUrl", finalUrl); etag?.let { put("etag", it) }; lastModified?.let { put("lastModified", it) }; put("total", total)
        }
        metaFile(name).writeText(o.toString())
    }

    private fun readMeta(name: String): Map<String, String>? = runCatching {
        Json.parseToJsonElement(metaFile(name).readText()).jsonObject.mapValues { (it.value as JsonPrimitive).content }
    }.getOrNull()

    companion object {
        fun checkName(n: String) {
            if (n.isBlank() || '/' in n || '\\' in n || n.startsWith(".")) throw IOException("Nome de arquivo inválido: $n")
        }

        /** Calcula o hash de um arquivo com progresso. */
        fun hashFile(f: File, algorithm: HashAlgorithm, listener: ProgressListener, cancel: CancellationSignal): String {
            val md = algorithm.newDigest()
            val buf = ByteArray(1 shl 20)
            val total = f.length()
            val start = System.nanoTime()
            var done = 0L
            var last = 0L
            f.inputStream().use { input ->
                while (true) {
                    cancel.throwIfCancelled()
                    val n = input.read(buf); if (n < 0) break
                    md.update(buf, 0, n); done += n
                    val now = System.nanoTime()
                    if (now - last > 300_000_000L) { last = now; listener.onProgress(Progress("Verificando", done, total, done / ((now - start) / 1e9).coerceAtLeast(0.001))) }
                }
            }
            listener.onProgress(Progress("Verificando", done, total, 0.0))
            return md.digest().toHex()
        }
    }
}
