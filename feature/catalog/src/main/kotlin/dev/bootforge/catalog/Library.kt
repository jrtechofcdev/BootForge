package dev.bootforge.catalog

import dev.bootforge.blockdev.ProgressListener
import dev.bootforge.integrity.CancellationSignal
import dev.bootforge.integrity.HashAlgorithm
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException

/** Situação de verificação de uma imagem — o app nunca diz "verificada" sem conferir de verdade. */
enum class ItemStatus(val label: String, val verified: Boolean) {
    SIGNED_OK("Verificada: assinatura oficial + checksum", true),
    CHECKSUM_OK("Verificada: checksum oficial", true),
    USER_CHECKSUM_OK("Verificada: checksum informado por você", true),
    UNVERIFIED("Não verificada: sem checksum oficial (autenticidade não comprovada)", false),
}

/** Imagem baixada e guardada na biblioteca do app. */
data class LibraryItem(
    val fileName: String, val title: String, val version: String?, val systemId: String?,
    val sizeBytes: Long, val sha256: String, val checksum: Checksum?, val status: ItemStatus,
    val signedBy: String?, val sourceUrl: String?, val addedAt: Long,
)

/**
 * Biblioteca das imagens baixadas (para reutilizar sem baixar de novo). Índice em
 * "biblioteca.json" na mesma pasta dos arquivos. Itens cujo arquivo sumiu são ignorados.
 */
class Library(val dir: File) {
    private val index = File(dir, "biblioteca.json")

    init { dir.mkdirs() }

    @Synchronized
    fun items(): List<LibraryItem> = load().filter { File(dir, it.fileName).isFile }.sortedByDescending { it.addedAt }

    fun file(item: LibraryItem) = File(dir, item.fileName)

    /** Imagem já baixada para este sistema/versão (para reutilizar). */
    fun find(systemId: String?, version: String?, checksum: Checksum?): LibraryItem? = items().firstOrNull { i ->
        (checksum != null && i.checksum?.hex == checksum.hex) || (systemId != null && i.systemId == systemId && i.version == version && i.status.verified)
    }

    /** Downloads pausados/interrompidos (arquivos .part). */
    fun partials(): List<Pair<String, Long>> = dir.listFiles { f -> f.name.endsWith(".part") }?.map { it.name.removeSuffix(".part") to it.length() } ?: emptyList()

    /**
     * Calcula o hash do arquivo e compara com o checksum oficial; só então registra.
     * Checksum diferente = arquivo corrompido ou adulterado: é APAGADO e o erro é informado.
     */
    fun verifyAndAdd(file: File, req: DownloadRequest, listener: ProgressListener, cancel: CancellationSignal, userChecksum: Boolean = false): LibraryItem {
        val expected = req.checksum
        val sha256 = Downloader.hashFile(file, HashAlgorithm.SHA256, listener, cancel)
        val actual = when (expected?.algorithm) {
            null, "SHA-256" -> sha256
            else -> Downloader.hashFile(file, HashAlgorithm.SHA512, listener, cancel)
        }
        val status = when {
            expected == null -> ItemStatus.UNVERIFIED
            !actual.equals(expected.hex, ignoreCase = true) -> {
                file.delete()
                throw IOException("CHECKSUM NÃO CONFERE: o arquivo baixado está corrompido ou foi adulterado e foi apagado. Esperado ${expected.hex.take(16)}…, obtido ${actual.take(16)}…")
            }
            userChecksum -> ItemStatus.USER_CHECKSUM_OK
            req.verification == Verification.SIGNED -> ItemStatus.SIGNED_OK
            else -> ItemStatus.CHECKSUM_OK
        }
        val item = LibraryItem(file.name, req.title, req.version, req.systemId, file.length(), sha256, expected, status, req.signedBy, req.url, System.currentTimeMillis())
        add(item)
        return item
    }

    @Synchronized
    fun add(item: LibraryItem) = save(load().filter { it.fileName != item.fileName } + item)

    @Synchronized
    fun remove(fileName: String) {
        File(dir, fileName).delete()
        save(load().filter { it.fileName != fileName })
    }

    /** Apaga um download pausado. */
    fun discardPartial(fileName: String) {
        File(dir, "$fileName.part").delete(); File(dir, "$fileName.part.json").delete()
    }

    // ------------------------------------------------------------------ persistência

    private fun load(): List<LibraryItem> = runCatching {
        Json.parseToJsonElement(index.readText()).jsonArray.map { e ->
            val o = e.jsonObject
            fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            val cs = (o["checksum"] as? JsonObject)?.let { c -> Checksum((c["algorithm"] as JsonPrimitive).content, (c["hex"] as JsonPrimitive).content, (c["source"] as? JsonPrimitive)?.content ?: "") }
            LibraryItem(s("fileName")!!, s("title") ?: s("fileName")!!, s("version"), s("systemId"), s("sizeBytes")?.toLong() ?: 0, s("sha256") ?: "",
                cs, runCatching { ItemStatus.valueOf(s("status")!!) }.getOrDefault(ItemStatus.UNVERIFIED), s("signedBy"), s("sourceUrl"), s("addedAt")?.toLong() ?: 0)
        }
    }.getOrDefault(emptyList())

    private fun save(items: List<LibraryItem>) {
        val arr: JsonArray = buildJsonArray {
            for (i in items) add(buildJsonObject {
                put("fileName", i.fileName); put("title", i.title); i.version?.let { put("version", it) }; i.systemId?.let { put("systemId", it) }
                put("sizeBytes", i.sizeBytes); put("sha256", i.sha256); put("status", i.status.name)
                i.signedBy?.let { put("signedBy", it) }; i.sourceUrl?.let { put("sourceUrl", it) }; put("addedAt", i.addedAt)
                i.checksum?.let { c -> put("checksum", buildJsonObject { put("algorithm", c.algorithm); put("hex", c.hex); put("source", c.source) }) }
            })
        }
        val tmp = File(dir, "biblioteca.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(index)) { index.delete(); tmp.renameTo(index) }
    }
}
