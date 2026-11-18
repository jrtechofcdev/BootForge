package dev.bootforge.cli

import dev.bootforge.blockdev.FileBlockDevice
import dev.bootforge.blockdev.slice
import dev.bootforge.fs.exfat.ExFatVolume
import dev.bootforge.fs.ext.ExtFormatter
import dev.bootforge.iso.ImageInspector
import dev.bootforge.ventoy.CheckItem
import dev.bootforge.ventoy.ImageCatalog
import dev.bootforge.ventoy.ThemeManager
import dev.bootforge.ventoy.VentoyConfigStore
import dev.bootforge.ventoy.VentoyDetector
import dev.bootforge.ventoy.VentoyMaintenance
import dev.bootforge.ventoy.VentoyPackage
import dev.bootforge.ventoy.VentoyPersistence
import dev.bootforge.ventoy.VentoyUpdater
import java.io.File
import java.io.IOException

/** Comandos de manutenção do Ventoy (mesmo núcleo usado pelo app). */
internal fun ventoyCommand(cmd: String, image: File, args: Array<String>): Boolean {
    if (cmd !in setOf("check", "secureboot", "catalog", "isoinfo", "json", "control", "theme", "persist")) return false
    val pkg = VentoyPackage.fromDirectory(File(args.indexOf("--ventoy").takeIf { it >= 0 }?.let { args[it + 1] } ?: "third_party/ventoy-1.1.18"))
    val a = args.drop(2).filter { !it.startsWith("--") }
    val readOnly = cmd in setOf("check", "catalog", "isoinfo") || (cmd == "json" && a.getOrNull(0) != "set")
    FileBlockDevice.open(image, readOnly = readOnly).use { dev ->
        val info = VentoyDetector.inspect(dev, scanData = false)
        if (!info.isVentoy) throw IOException("A imagem não contém o Ventoy")
        val data = dev.slice(info.part1Start!!, info.part1Sectors!!)
        fun vol() = ExFatVolume.open(data, writable = !readOnly)
        when (cmd) {
            "check" -> VentoyMaintenance.checkInstallation(dev, pkg).forEach {
                val tag = when (it.level) { CheckItem.Level.OK -> "ok"; CheckItem.Level.WARNING -> "AVISO"; CheckItem.Level.ERROR -> "ERRO"; CheckItem.Level.INFO -> "info" }
                println("[$tag] ${it.title}${if (it.detail.isNotBlank()) " — " + it.detail.replace("\n", "; ") else ""}")
            }
            "secureboot" -> {
                val on = when (a.getOrNull(0)) { "on" -> true; "off" -> false; else -> throw IOException("Use on ou off") }
                val r = VentoyUpdater(pkg).update(dev, dev.sizeBytes, secureBoot = on)
                println("Secure Boot ${if (r.detected.secureBootSupport == true) "LIGADO" else "DESLIGADO"} (Ventoy ${r.toVersion})")
            }
            "catalog" -> {
                val v = vol()
                ImageCatalog.scan(v, ImageCatalog.rulesFrom(VentoyConfigStore(v).load().first, v)).forEach {
                    println((if (it.visible) "  [menu] " else "  [oculta] ") + it.path + "  (${it.size} bytes)" + (it.hiddenReason?.let { r -> " — $r" } ?: "") + if (it.uefiOnly) " [só UEFI]" else "")
                }
            }
            "isoinfo" -> {
                val r = vol().openReader(a[0]).use { ImageInspector.inspect(a[0].substringAfterLast('/'), it) }
                println("Formato: ${r.format.label}  Rótulo: ${r.volumeId}  Sistema: ${r.os?.name ?: "não identificado"} (${r.os?.evidence ?: "-"})")
                println("Boot BIOS: ${r.bootBios}  UEFI: ${r.bootUefi}  Híbrida MBR/GPT: ${r.hybridMbr}/${r.hybridGpt}  Categoria: ${r.os?.category?.folder ?: "-"}")
                r.problems.forEach { println("  problema: $it") }
            }
            "json" -> {
                val store = VentoyConfigStore(vol())
                when (a.getOrNull(0)) {
                    "show" -> println(store.readText() ?: "(sem ventoy.json)")
                    "validate" -> {
                        val (cfg, syntax) = store.load()
                        val issues = syntax + (cfg?.let(store::validate) ?: emptyList())
                        if (issues.isEmpty()) println("ventoy.json sem problemas")
                        issues.forEach { println("${it.severity}: ${it.where}${it.line?.let { l -> " (linha $l, coluna ${it.column})" } ?: ""} — ${it.message}") }
                    }
                    "set" -> println("Salvo. Backup: ${store.saveText(File(a[1]).readText()) ?: "(não havia arquivo anterior)"}")
                    else -> throw IOException("Use show, validate ou set")
                }
            }
            "control" -> {
                val store = VentoyConfigStore(vol())
                val cfg = store.load().first ?: throw IOException("ventoy.json com erro de sintaxe")
                store.save(cfg.withControl(a[0], a[1].takeIf { it != "-" }))
                println("${a[0]} = ${a[1]}")
            }
            "theme" -> {
                val v = vol()
                val tm = ThemeManager(v, VentoyConfigStore(v))
                when (a.getOrNull(0)) {
                    "install-original" -> println("Tema original copiado para ${tm.installOriginal(pkg, overwrite = true)}")
                    "apply" -> { tm.apply(listOf(a[1])); println("Tema aplicado: ${a[1]}") }
                    "default" -> { tm.restoreDefault(); println("Tema padrão do Ventoy restaurado") }
                    else -> throw IOException("Use install-original, apply ou default")
                }
            }
            "persist" -> {
                val v = vol()
                val preset = if (a.getOrNull(2) == "debian") VentoyPersistence.Preset.DEBIAN else VentoyPersistence.Preset.UBUNTU
                val link = args.indexOf("--link").takeIf { it >= 0 }?.let { args[it + 1] }
                val r = VentoyPersistence.create(
                    data, v, VentoyConfigStore(v),
                    VentoyPersistence.Request(a[0], a[1].toLong(), if ("--ext2" in args) ExtFormatter.Type.EXT2 else ExtFormatter.Type.EXT3,
                        preset.label, preset.configFile, linkImage = link),
                )
                println("Persistência criada: ${a[0]} (${r.blocks} blocos de ${r.blockSize} B, journal ${r.journalBlocks})")
            }
        }
    }
    return true
}
