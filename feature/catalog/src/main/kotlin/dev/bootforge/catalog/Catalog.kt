package dev.bootforge.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

enum class Category(val label: String) { WINDOWS("Windows"), LINUX("Linux"), TOOLS("Ferramentas") }

/** Grupo de chaves OpenPGP oficiais (arquivos embutidos no app + impressões digitais publicadas). */
data class KeyGroup(val id: String, val label: String, val files: List<String>, val fingerprints: Set<String>, val published: String)

/** Um sistema do catálogo, como definido no catalog.json. */
data class SystemDef(
    val id: String, val name: String, val vendor: String, val category: Category, val arch: String,
    val description: String, val officialPage: String,
    /** Tipo de resolvedor + parâmetros (ver [Resolvers]). */
    val resolver: Map<String, String>,
    val notes: List<String>,
) {
    val resolverType: String get() = resolver["type"] ?: "browser"
    val browserOnly: Boolean get() = resolverType == "browser"
}

data class CatalogDefinition(val schema: Int, val updated: String, val keys: Map<String, KeyGroup>, val systems: List<SystemDef>) {

    companion object {
        const val SUPPORTED_SCHEMA = 1
        private val json = Json { ignoreUnknownKeys = true }

        /** Catálogo embutido no app. */
        fun bundled(): CatalogDefinition = parse(
            CatalogDefinition::class.java.getResourceAsStream("/dev/bootforge/catalog/catalog.json")!!.use { String(it.readBytes(), Charsets.UTF_8) },
        )

        /** Lê e valida um catalog.json (o embutido ou uma atualização baixada). */
        fun parse(text: String): CatalogDefinition {
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse { throw IOException("catalog.json inválido: ${it.message}") }
            val schema = root["schema"]?.jsonPrimitive?.content?.toIntOrNull() ?: throw IOException("catalog.json sem \"schema\"")
            if (schema > SUPPORTED_SCHEMA) throw IOException("Catálogo exige uma versão mais nova do BootForge (schema $schema)")
            val keys = (root["keys"] as? JsonObject).orEmpty().mapValues { (id, v) ->
                val o = v.jsonObject
                KeyGroup(id, o.str("label") ?: id, o.list("files"), o.list("fingerprints").map { it.uppercase().replace(" ", "") }.toSet(), o.str("published") ?: "")
            }
            val systems = (root["systems"] as? JsonArray ?: throw IOException("catalog.json sem \"systems\"")).map { e ->
                val o = e.jsonObject
                val id = o.str("id") ?: throw IOException("Sistema sem id")
                val resolver = (o["resolver"] as? JsonObject ?: throw IOException("$id: sem resolver")).mapValues { (_, v) -> (v as JsonPrimitive).content }
                if (resolver["type"] !in Resolvers.TYPES) throw IOException("$id: resolvedor desconhecido \"${resolver["type"]}\"")
                resolver["keys"]?.let { k -> if (k !in keys) throw IOException("$id: grupo de chaves \"$k\" não existe") }
                SystemDef(
                    id, o.str("name") ?: id, o.str("vendor") ?: "", runCatching { Category.valueOf(o.str("category") ?: "LINUX") }.getOrDefault(Category.LINUX),
                    o.str("arch") ?: "", o.str("description") ?: "", o.str("officialPage") ?: "", resolver, o.list("notes"),
                )
            }
            if (systems.map { it.id }.toSet().size != systems.size) throw IOException("catalog.json com ids repetidos")
            return CatalogDefinition(schema, root.str("updated") ?: "", keys, systems)
        }

        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.list(k: String) = (this[k] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
    }
}

/** Como a autenticidade da imagem pode ser comprovada. */
enum class Verification(val label: String, val detail: String) {
    SIGNED("Assinatura oficial", "Checksum assinado digitalmente pela chave oficial do projeto (OpenPGP), conferida pelo app."),
    OFFICIAL_CHECKSUM("Checksum oficial", "Checksum publicado pela fonte oficial (HTTPS). Não há assinatura digital para conferir."),
    NONE("Sem verificação automática", "A fonte não publica checksum consultável automaticamente: a autenticidade não pode ser comprovada pelo app."),
}

/** Checksum esperado de um arquivo. */
data class Checksum(val algorithm: String, val hex: String, val source: String) {
    init { require(algorithm == "SHA-256" || algorithm == "SHA-512") }
}

/** Resultado da consulta de um sistema: versão atual, link e como verificar. */
data class ResolvedImage(
    val system: SystemDef,
    val version: String?,
    val downloadUrl: String?,
    val fileName: String?,
    val sizeBytes: Long?,
    val checksum: Checksum?,
    val verification: Verification,
    /** Chave que assinou (impressão digital) quando [Verification.SIGNED]. */
    val signedBy: String? = null,
    val notes: List<String> = emptyList(),
) {
    val host: String? get() = downloadUrl?.let { runCatching { java.net.URL(it).host }.getOrNull() }
}
