package dev.bootforge.catalog

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Consulta as FONTES REAIS (internet). Só roda com BOOTFORGE_LIVE=1 — serve para detectar
 * quando um site oficial muda de formato (aí basta atualizar o catalog.json).
 */
class LiveCatalogTest {
    @Test
    fun resolvesEveryOfficialSource() {
        if (System.getenv("BOOTFORGE_LIVE") != "1") return
        val def = CatalogDefinition.bundled()
        val keys = def.keys.mapValues { OpenPgp.loadKeys(it.value) }
        val res = Resolvers(UrlConnectionHttp()) { keys.getValue(it) }
        val failures = mutableListOf<String>()
        for (s in def.systems) {
            val r = runCatching { res.resolve(s) }
            r.onSuccess { i ->
                println("OK  %-20s v=%-12s %-18s %s %s %s".format(s.id, i.version, i.verification.name, i.sizeBytes, i.checksum?.let { it.algorithm + ":" + it.hex.take(12) }, i.downloadUrl))
            }.onFailure { e -> println("ERRO %-20s %s".format(s.id, e.message)); failures += "${s.id}: ${e.message}" }
        }
        // GitHub é bloqueado em alguns ambientes de teste; o resto precisa funcionar
        assertTrue(failures.none { !it.startsWith("ventoy") }, failures.joinToString("\n"))
    }
}
