package dev.bootforge.catalog

import dev.bootforge.integrity.Diag
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Acesso HTTP mínimo usado pelo catálogo (metadados pequenos). É uma interface para os testes
 * poderem simular as fontes oficiais sem internet.
 */
interface Http {
    /** Baixa um recurso pequeno (páginas, JSON, arquivos de checksum). */
    fun bytes(url: String, maxBytes: Int = 8 shl 20): ByteArray
    fun text(url: String, maxBytes: Int = 8 shl 20): String = String(bytes(url, maxBytes), Charsets.UTF_8)
    /** Tamanho anunciado pelo servidor (HEAD), ou null. */
    fun contentLength(url: String): Long?
}

/** Erro HTTP com o código de resposta. */
class HttpException(val code: Int, url: String) : IOException("HTTP $code em $url")

/** Implementação com HttpURLConnection (funciona no Android e no PC). */
class UrlConnectionHttp(
    private val userAgent: String = USER_AGENT,
    private val connectTimeoutMs: Int = 20_000,
    private val readTimeoutMs: Int = 30_000,
) : Http {

    override fun bytes(url: String, maxBytes: Int): ByteArray = retrying {
        val c = open(url, "GET")
        try {
            if (c.responseCode !in 200..299) throw HttpException(c.responseCode, url)
            c.inputStream.use { readLimited(it, maxBytes, url) }
        } finally {
            c.disconnect()
        }
    }

    /**
     * Repete em falhas TEMPORÁRIAS de rede (conexão reiniciada, tempo esgotado, erro 5xx),
     * comuns em rede móvel. Erros definitivos (404, 403…) não são repetidos.
     */
    private fun <T> retrying(block: () -> T): T {
        var last: IOException? = null
        for (attempt in 0 until RETRIES) {
            try { return block() } catch (e: HttpException) {
                if (e.code < 500) throw e
                last = e
            } catch (e: IOException) { last = e }
            Diag.w("HTTP", "Tentativa ${attempt + 1}/$RETRIES falhou: ${last?.message}")
            Thread.sleep(1000L shl attempt)
        }
        throw last!!
    }

    override fun contentLength(url: String): Long? = runCatching {
        val c = open(url, "HEAD")
        try { if (c.responseCode in 200..299) c.contentLengthLong.takeIf { it > 0 } else null } finally { c.disconnect() }
    }.getOrNull()

    /** Abre a conexão seguindo redirecionamentos (inclusive http→https), no máximo 10. */
    fun open(url: String, method: String, headers: Map<String, String> = emptyMap()): HttpURLConnection {
        var current = url
        repeat(10) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.requestMethod = method
            c.connectTimeout = connectTimeoutMs
            c.readTimeout = readTimeoutMs
            c.setRequestProperty("User-Agent", userAgent)
            c.setRequestProperty("Accept-Encoding", "identity")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            val code = c.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Redirecionamento sem destino em $current")
                c.disconnect()
                val next = URL(URL(current), loc)
                if (next.protocol != "https" && URL(current).protocol == "https") throw IOException("Redirecionamento inseguro (https → http) recusado: $next")
                current = next.toString()
                return@repeat
            }
            return c
        }
        throw IOException("Redirecionamentos demais: $url")
    }

    private fun readLimited(input: InputStream, max: Int, url: String): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf); if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw IOException("Resposta grande demais de $url")
        }
        return out.toByteArray()
    }

    companion object {
        /** Identificação genérica do app (sem dados do usuário). */
        const val RETRIES = 4
        const val USER_AGENT = "BootForge/0.5 (Android)"
    }
}
