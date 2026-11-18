package dev.bootforge.cli

import dev.bootforge.blockdev.FileBlockDevice
import dev.bootforge.blockdev.ProgressListener
import dev.bootforge.blockdev.RandomReader
import dev.bootforge.isowriter.IsoAnalyzer
import dev.bootforge.isowriter.IsoWriter
import dev.bootforge.isowriter.PartitionScheme
import dev.bootforge.isowriter.WriteMethod
import dev.bootforge.isowriter.WriteRequest
import java.io.File

/**
 * Comandos da Etapa 3 (gravação de ISOs) — o mesmo motor do app, gravando em arquivos .img:
 *   iso-analyze <arquivo.iso>
 *   iso-write   <arquivo.iso> <destino.img> [--method dd|windows|extract] [--gpt] [--no-verify]
 */
internal fun isoCommand(cmd: String, iso: File, args: Array<String>): Boolean {
    when (cmd) {
        "iso-analyze" -> RandomReader.of(iso).use { src ->
            val a = IsoAnalyzer.analyze(iso.name, src)
            println("Imagem: ${a.info.fileName}  ${a.info.size} bytes  formato=${a.info.format.label}  árvore=${a.treeFormat ?: "-"}")
            println("Rótulo: ${a.info.volumeId ?: "-"}   SO: ${a.info.os?.name ?: "não identificado"}")
            println("Boot: BIOS=${a.info.bootBios} UEFI=${a.info.bootUefi} híbrida(MBR/GPT)=${a.info.hybridMbr}/${a.info.hybridGpt}")
            println("Arquivos: ${a.fileCount} (${a.totalFileBytes} bytes)  carregadores UEFI: ${a.efiLoaders.ifEmpty { listOf("nenhum") }.joinToString()}")
            a.windowsImage?.let { println("Windows: ${it.path} ${it.size} bytes${if (it.problem != null) " — ${it.problem}" else ""}") }
            a.largeFiles.forEach { println("  > 4 GB: ${it.path} (${it.size})") }
            println("Recomendado: ${a.recommended?.title ?: "nenhum"} — ${a.recommendationReason}")
            a.methods.forEach { m ->
                println("\n[${if (m.available) "disponível" else "indisponível"}] ${m.method.title}  (mínimo ${m.requiredBytes / 1048576} MiB)")
                m.blockers.forEach { println("   X $it") }
                m.notes.forEach { println("   - $it") }
            }
        }
        "iso-write" -> {
            val target = File(args.getOrNull(2) ?: return false)
            if (!target.isFile || target.path.startsWith("/dev/")) throw IllegalArgumentException("Destino deve ser um arquivo .img existente (use create)")
            RandomReader.of(iso).use { src ->
                val a = IsoAnalyzer.analyze(iso.name, src)
                val method = when (args.indexOf("--method").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }) {
                    null -> a.recommended ?: throw IllegalStateException("Nenhum método disponível")
                    "dd" -> WriteMethod.DD
                    "windows" -> WriteMethod.WINDOWS_FAT32
                    "extract" -> WriteMethod.EXTRACT_FAT32
                    else -> return false
                }
                val req = WriteRequest(method, if ("--gpt" in args) PartitionScheme.GPT else PartitionScheme.MBR, verify = "--no-verify" !in args)
                println("Método: ${method.title}${if (method != WriteMethod.DD) " / ${req.scheme.title}" else ""}")
                val r = FileBlockDevice.open(target).use { dev ->
                    IsoWriter.write(dev, src, a, req, ProgressListener { p ->
                        print("\r  ${p.stage}: ${(p.fraction * 100).toInt()}%  ${p.bytesDone / 1048576} MiB   ")
                    })
                }
                println("\nConcluído: ${r.bytesWritten} bytes, ${r.filesWritten} arquivos, verificado=${r.verified}" +
                    (r.label?.let { ", rótulo $it" } ?: "") + (r.imageSha256?.let { ", SHA-256 $it" } ?: ""))
                r.wimParts.forEach { println("  parte WIM: $it") }
            }
        }
        else -> return false
    }
    return true
}
