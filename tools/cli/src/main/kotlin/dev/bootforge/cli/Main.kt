package dev.bootforge.cli

import dev.bootforge.blockdev.FileBlockDevice
import dev.bootforge.blockdev.Progress
import dev.bootforge.partition.DiskLayoutReader
import dev.bootforge.ventoy.InstallObserver
import dev.bootforge.ventoy.InstallStep
import dev.bootforge.ventoy.PartitionStyle
import dev.bootforge.ventoy.VentoyDetector
import dev.bootforge.ventoy.VentoyInstallOptions
import dev.bootforge.ventoy.VentoyInstaller
import dev.bootforge.ventoy.VentoyPackage
import dev.bootforge.ventoy.VentoyUpdater
import java.io.File
import kotlin.system.exitProcess

/**
 * bootforge-cli — usa o MESMO núcleo do app em arquivos de imagem de disco (.img).
 *
 * Exemplos:
 *   bootforge-cli create  disco.img 8G
 *   bootforge-cli install disco.img --gpt --ventoy third_party/ventoy-1.1.18
 *   bootforge-cli inspect disco.img
 *   bootforge-cli update  disco.img --ventoy third_party/ventoy-1.1.18
 *
 * Por segurança, esta ferramenta só aceita ARQUIVOS comuns (nunca /dev/sdX nem \\.\PhysicalDrive).
 */
fun main(args: Array<String>) {
    try {
        run(args)
    } catch (e: Exception) {
        fail("${e.javaClass.simpleName}: ${e.message}")
    }
}

private fun run(args: Array<String>) {
    if (args.isEmpty()) usage()
    val cmd = args[0]
    val file = args.getOrNull(1)?.let(::File) ?: usage()
    val ventoyDir = File(option(args, "--ventoy") ?: "third_party/ventoy-1.1.18")

    if (cmd != "create" && !file.isFile) fail("Arquivo não encontrado: $file")
    if (file.path.startsWith("/dev/") || file.path.startsWith("\\\\.\\")) fail("Apenas arquivos de imagem são aceitos")

    when (cmd) {
        "create" -> {
            val size = parseSize(args.getOrNull(2) ?: usage())
            FileBlockDevice.create(file, size).close()
            println("Imagem esparsa criada: $file (${size} bytes)")
        }
        "inspect" -> FileBlockDevice.open(file, readOnly = true).use { dev ->
            val layout = DiskLayoutReader.read(dev)
            println("Esquema: ${layout.scheme.label}   setores: ${layout.totalSectors} x ${layout.sectorSize}")
            layout.partitions.forEach {
                println("  #${it.number} ${it.startLba}..${it.endLba} ${it.typeDescription} ${it.name ?: ""} fs=${it.fs?.type?.label} label=${it.fs?.label ?: ""}${if (it.bootable) " [ativa]" else ""}")
            }
            layout.warnings.forEach { println("  aviso: $it") }
            val info = VentoyDetector.inspect(dev)
            println("Ventoy: ${if (info.isVentoy) "SIM, versão ${info.version}" else "não"}")
            info.checks.forEach { println("  [${if (it.passed) "ok" else "X"}] ${it.description}") }
            info.secureBootSupport?.let { println("  Secure Boot: ${if (it) "suportado" else "não"}") }
            info.diskUuid?.let { println("  UUID do disco: $it") }
            info.data?.let { d ->
                println("  Dados: ${d.fsType.label} '${d.label ?: ""}' total=${d.totalBytes} livre=${d.freeBytes} imagens=${d.bootableImageCount} isos=${d.isoCount} arquivos=${d.fileCount}")
                d.note?.let { println("  obs.: $it") }
            }
            info.warnings.forEach { println("  aviso: $it") }
        }
        "install" -> FileBlockDevice.open(file).use { dev ->
            val opts = VentoyInstallOptions(
                style = if ("--gpt" in args) PartitionStyle.GPT else PartitionStyle.MBR,
                reserveMiB = option(args, "--reserve")?.toLong() ?: 0,
                align4k = "--no-align" !in args,
            )
            val report = VentoyInstaller(VentoyPackage.fromDirectory(ventoyDir)).install(dev, opts, dev.sizeBytes, ConsoleObserver)
            println("\nConcluído: Ventoy ${report.version} (${report.layout.style}) — VTOYEFI SHA-256 ${report.efiSha256}")
        }
        "update" -> FileBlockDevice.open(file).use { dev ->
            val r = VentoyUpdater(VentoyPackage.fromDirectory(ventoyDir)).update(dev, dev.sizeBytes, ConsoleObserver)
            println("\nAtualizado: ${r.fromVersion} → ${r.toVersion}")
        }
        else -> usage()
    }
}

private object ConsoleObserver : InstallObserver {
    override fun onStep(step: InstallStep, index: Int, total: Int) = println("[${index + 1}/$total] ${step.label}")
    override fun onProgress(progress: Progress) =
        print("\r      ${(progress.fraction * 100).toInt()}%  ${progress.bytesDone / 1048576} MiB  ${"%.1f".format(progress.bytesPerSecond / 1048576)} MiB/s   ")
    override fun onLog(message: String) { if (!message.startsWith("»")) println("      $message") }
}

private fun option(args: Array<String>, name: String): String? =
    args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }

private fun parseSize(s: String): Long {
    val m = Regex("""(\d+)([KMGT]?)""").matchEntire(s.uppercase()) ?: fail("Tamanho inválido: $s")
    val n = m.groupValues[1].toLong()
    return n shl when (m.groupValues[2]) { "K" -> 10; "M" -> 20; "G" -> 30; "T" -> 40; else -> 0 }
}

private fun usage(): Nothing {
    println(
        """
        Uso:
          bootforge-cli create  <imagem.img> <tamanho, ex. 8G>
          bootforge-cli inspect <imagem.img>
          bootforge-cli install <imagem.img> [--gpt] [--reserve MiB] [--no-align] [--ventoy <pasta>]
          bootforge-cli update  <imagem.img> [--ventoy <pasta>]
        """.trimIndent(),
    )
    exitProcess(2)
}

private fun fail(msg: String): Nothing {
    System.err.println("Erro: $msg")
    exitProcess(1)
}
