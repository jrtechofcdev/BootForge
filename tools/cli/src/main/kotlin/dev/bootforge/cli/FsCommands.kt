package dev.bootforge.cli

import dev.bootforge.blockdev.BlockDevice
import dev.bootforge.blockdev.FileBlockDevice
import dev.bootforge.blockdev.slice
import dev.bootforge.fs.exfat.ExFatVolume
import dev.bootforge.partition.DiskLayoutReader
import dev.bootforge.partition.FsType
import dev.bootforge.ventoy.VentoyDetector
import java.io.File
import java.io.IOException

/**
 * Comandos de arquivo da CLI. Atuam na partição de dados do Ventoy (partição 1) ou,
 * se não houver Ventoy, na primeira partição exFAT (ou no disco inteiro, se for exFAT).
 */
internal fun fsCommand(cmd: String, image: File, args: Array<String>): Boolean {
    if (ventoyCommand(cmd, image, args)) return true
    val writes = cmd in setOf("put", "mkdir", "rm", "mv", "ren")
    if (cmd !in setOf("ls", "get") && !writes) return false
    FileBlockDevice.open(image, readOnly = !writes).use { dev ->
        val vol = ExFatVolume.open(dataVolume(dev), writable = writes)
        val a = args.drop(2).filter { !it.startsWith("--") && it != "-r" }
        when (cmd) {
            "ls" -> {
                val path = a.getOrElse(0) { "/" }
                vol.list(path).sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).forEach {
                    println(if (it.isDirectory) "  [pasta]  ${it.name}" else "  %12d  %s%s".format(it.size, it.name, if (it.contiguous || it.size == 0L) "" else "  (fragmentado)"))
                }
                println("Livre: ${vol.freeBytes()} de ${vol.totalBytes} bytes")
            }
            "put" -> {
                val src = File(a[0])
                val r = src.inputStream().buffered(1 shl 20).use {
                    vol.writeFile(a[1], it, src.length(), overwrite = "--overwrite" in args, modifiedMillis = src.lastModified())
                }
                println("Gravado ${a[1]} (${src.length()} bytes, ${r.fragments} fragmento(s)) SHA-256 ${r.sha256}")
            }
            "get" -> {
                vol.openFile(a[0]).use { input -> File(a[1]).outputStream().use { input.copyTo(it, 1 shl 20) } }
                println("Extraído para ${a[1]}")
            }
            "mkdir" -> { vol.mkdirs(a[0]); println("Pasta criada: ${a[0]}") }
            "rm" -> { vol.delete(a[0], recursive = "-r" in args); println("Excluído: ${a[0]}") }
            "mv" -> { vol.move(a[0], a[1]); println("Movido: ${a[0]} → ${a[1]}") }
            "ren" -> { vol.rename(a[0], a[1]); println("Renomeado para ${a[1]}") }
        }
    }
    return true
}

internal fun dataVolume(dev: BlockDevice): BlockDevice {
    val v = VentoyDetector.inspect(dev, scanData = false)
    if (v.isVentoy) return dev.slice(v.part1Start!!, v.part1Sectors!!)
    val layout = DiskLayoutReader.read(dev)
    layout.partitions.firstOrNull { it.fs?.type == FsType.EXFAT }?.let { return dev.slice(it.startLba, it.sectorCount) }
    if (layout.wholeDiskFs?.type == FsType.EXFAT) return dev
    throw IOException("Nenhum volume exFAT encontrado na imagem")
}
