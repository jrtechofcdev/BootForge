package dev.bootforge.cli

import dev.bootforge.blockdev.ProgressListener
import dev.bootforge.catalog.CatalogDefinition
import dev.bootforge.catalog.DownloadControl
import dev.bootforge.catalog.DownloadRequest
import dev.bootforge.catalog.Downloader
import dev.bootforge.catalog.Library
import dev.bootforge.catalog.OpenPgp
import dev.bootforge.catalog.Resolvers
import dev.bootforge.catalog.UrlConnectionHttp
import java.io.File

/**
 * Comandos da Etapa 6 (catálogo e downloads), o mesmo motor do app:
 *   catalog                         consulta as versões atuais em todas as fontes oficiais
 *   catalog-download <id> <pasta>   baixa (ou continua) e verifica; Ctrl+C e rodar de novo = retomar
 */
internal fun catalogCommand(args: Array<String>): Boolean {
    val cmd = args.getOrNull(0) ?: return false
    if (cmd != "catalog" && cmd != "catalog-download") return false
    val def = CatalogDefinition.bundled()
    val keys = def.keys.mapValues { OpenPgp.loadKeys(it.value) }
    val resolvers = Resolvers(UrlConnectionHttp()) { keys.getValue(it) }
    when (cmd) {
        "catalog" -> {
            println("Catálogo ${def.updated} — ${def.systems.size} sistemas (fontes oficiais)\n")
            for (s in def.systems) {
                val r = runCatching { resolvers.resolve(s) }
                r.onSuccess { i ->
                    println("%-20s %-14s %-26s %s".format(s.id, i.version ?: "-", i.verification.label, i.sizeBytes?.let { "%.2f GB".format(it / 1e9) } ?: ""))
                    i.downloadUrl?.let { println("    $it") }
                    i.signedBy?.let { println("    assinado por $it") }
                }.onFailure { println("%-20s ERRO: %s".format(s.id, it.message)) }
            }
        }
        "catalog-download" -> {
            val id = args.getOrNull(1) ?: return false
            val dir = File(args.getOrNull(2) ?: return false)
            val sys = def.systems.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Sistema desconhecido: $id (veja \"catalog\")")
            val r = resolvers.resolve(sys)
            println("${sys.name} ${r.version} — ${r.verification.label}\n${r.downloadUrl}")
            val req = DownloadRequest.of(r)
            val dl = Downloader(dir)
            dl.partialBytes(req).takeIf { it > 0 }?.let { println("Continuando de ${it / 1048576} MiB") }
            val item = dl.download(req, Library(dir), ProgressListener { p ->
                print("\r  ${p.stage}: ${(p.fraction * 100).toInt()}%  ${p.bytesDone / 1048576} MiB  ${"%.1f".format(p.bytesPerSecond / 1048576)} MiB/s   ")
            }, DownloadControl())
            println("\nConcluído: ${File(dir, item.fileName)}\n${item.status.label}\nSHA-256 ${item.sha256}")
        }
    }
    return true
}
