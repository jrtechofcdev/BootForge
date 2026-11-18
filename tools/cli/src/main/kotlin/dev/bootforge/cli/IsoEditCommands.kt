package dev.bootforge.cli

import dev.bootforge.blockdev.ProgressListener
import dev.bootforge.blockdev.RandomReader
import dev.bootforge.isoedit.DirectorySink
import dev.bootforge.isoedit.EditSession
import dev.bootforge.isoedit.FileContent
import dev.bootforge.isoedit.IsoBuilder
import dev.bootforge.isoedit.IsoExtractor
import dev.bootforge.isoedit.IsoSource
import dev.bootforge.isoedit.IsoValidator
import dev.bootforge.isoedit.Paths
import dev.bootforge.isoedit.RebuildMode
import dev.bootforge.isoedit.VDir
import dev.bootforge.isoedit.VFile
import java.io.File

/**
 * Comandos da Etapa 5 (editor de ISO), o mesmo motor do app:
 *   iso-info    <arquivo.iso>
 *   iso-ls      <arquivo.iso> [/pasta]
 *   iso-extract <arquivo.iso> <pasta-destino> [/caminho ...]
 *   iso-rebuild <arquivo.iso> <nova.iso> [--full] [--label NOME] [operações...]
 *       --put local:/destino   --text /caminho=arquivo-local   --rm /caminho   --mkdir /pasta
 *       --mv /item:/pasta-destino   --ren /item:novo-nome
 */
internal fun isoEditCommand(cmd: String, iso: File, args: Array<String>): Boolean {
    if (cmd !in setOf("iso-info", "iso-ls", "iso-extract", "iso-rebuild")) return false
    RandomReader.of(iso).use { r ->
        val src = IsoSource.open(r)
        when (cmd) {
            "iso-info" -> {
                println("Imagem: ${iso.name} (${r.size} bytes)")
                println("Formato: ${src.formatLabel}")
                println("Rótulo: ${src.volumeId}")
                println("Conteúdo: ${Paths.countFiles(src.root)} arquivos, ${Paths.countDirs(src.root)} pastas")
                src.bootImages.forEach { println("Boot ${it.entry.platformName}: setor ${it.entry.lba}, ${it.path ?: "imagem oculta"} (${it.size} bytes)${if (it.bootInfoTable) ", boot-info-table" else ""}${if (it.grub2BootInfo) ", grub2-boot-info" else ""}") }
                IsoBuilder.availability(src).forEach { (m, why) -> println("${m.title}: ${why?.let { "indisponível — $it" } ?: "disponível"}${if (m == IsoBuilder.recommended(src)) " (recomendado)" else ""}") }
                (src.warnings + src.blockers).forEach { println("Aviso: $it") }
            }
            "iso-ls" -> {
                val path = args.getOrNull(2) ?: "/"
                val dir = (if (Paths.split(path).isEmpty()) src.root else Paths.find(src.root, path)) as? VDir ?: throw IllegalArgumentException("Pasta não encontrada: $path")
                dir.children.forEach { n -> println(if (n is VDir) "[pasta] ${n.name}/" else "%12d  %s".format((n as VFile).size, n.name)) }
            }
            "iso-extract" -> {
                val dest = File(args.getOrNull(2) ?: return false)
                val res = IsoExtractor.extract(EditSession(src), args.drop(3), DirectorySink(dest))
                println("Extraídos ${res.files} arquivos e ${res.dirs} pastas (${res.bytes} bytes) para $dest")
            }
            "iso-rebuild" -> {
                val out = File(args.getOrNull(2) ?: return false)
                if (out.canonicalPath == iso.canonicalPath) throw IllegalArgumentException("A nova ISO não pode substituir a original")
                val s = EditSession(src)
                var i = 3
                while (i < args.size) {
                    val v = args.getOrNull(i + 1)
                    when (args[i]) {
                        "--put" -> { val (local, dest) = v!!.split(":", limit = 2); s.addFile(Paths.parent(dest), Paths.name(dest), FileContent(File(local)), overwrite = true) }
                        "--text" -> { val (p, local) = v!!.split("=", limit = 2); s.replaceText(p, File(local).readText()) }
                        "--rm" -> s.delete(v!!)
                        "--mkdir" -> s.mkdir(Paths.parent(v!!), Paths.name(v))
                        "--mv" -> { val (a, b) = v!!.split(":", limit = 2); s.move(a, b) }
                        "--ren" -> { val (a, b) = v!!.split(":", limit = 2); s.rename(a, b) }
                        "--full", "--label" -> {}
                        else -> throw IllegalArgumentException("Opção desconhecida: ${args[i]}")
                    }
                    i += if (args[i] == "--full") 1 else 2
                }
                s.history.forEach { println("• $it") }
                val mode = if ("--full" in args) RebuildMode.FULL else IsoBuilder.recommended(src)
                val label = args.indexOf("--label").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
                val plan = IsoBuilder.plan(s, mode, label)
                println("Modo: ${mode.title} · tamanho final ${plan.totalBytes} bytes")
                println(plan.bootSummary)
                plan.warnings.forEach { println("Aviso: $it") }
                val progress = ProgressListener { p -> print("\r  ${p.stage}: ${(p.fraction * 100).toInt()}%   ") }
                val sha = out.outputStream().buffered(1 shl 20).use { plan.write(it, progress) }
                println("\nSHA-256: $sha")
                val rep = RandomReader.of(out).use { IsoValidator.validate(plan, s, it, sha, true, progress) }
                println()
                rep.checks.forEach { println("  [${if (it.ok) "ok" else "X"}] ${it.title}: ${it.detail}") }
                println("Resultado: ${if (rep.ok) "VÁLIDA" else "COM PROBLEMAS"} — ${rep.boot.label}")
                if (!rep.ok) throw IllegalStateException("Validação falhou")
            }
        }
    }
    return true
}
