package dev.bootforge.ventoy

import com.sun.net.httpserver.HttpServer
import dev.bootforge.blockdev.FileBlockDevice
import dev.bootforge.blockdev.MemoryBlockDevice
import dev.bootforge.fs.fat.FatVolume
import dev.bootforge.ventoy.config.ConfigIssue
import dev.bootforge.ventoy.config.VentoyConfig
import dev.bootforge.blockdev.readBlocks
import dev.bootforge.blockdev.slice
import dev.bootforge.fs.exfat.ExFatVolume
import dev.bootforge.fs.ext.ExtFormatter
import dev.bootforge.integrity.Hashing
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Testes do núcleo da Etapa 4, sempre sobre um pendrive virtual com o Ventoy instalado. */
class Etapa4Test {

    private val ventoyDir = File(System.getProperty("bootforge.ventoyDir"))
    private val pkg = VentoyPackage.fromDirectory(ventoyDir)
    private val temp = mutableListOf<File>()

    @AfterTest
    fun cleanup() = temp.forEach { it.deleteRecursively() }

    private fun tmp(prefix: String, suffix: String = ".img") = File.createTempFile("bf-$prefix", suffix).also { temp += it }

    private class Disk(val file: File, val dev: FileBlockDevice, val info: VentoyInfo) {
        val data get() = dev.slice(info.part1Start!!, info.part1Sectors!!)
        fun vol(writable: Boolean = true) = ExFatVolume.open(data, writable)
    }

    private fun ventoyDisk(sizeMiB: Long = 512, secureBoot: Boolean = true): Disk {
        val f = tmp("v4")
        val dev = FileBlockDevice.create(f, sizeMiB shl 20)
        val r = VentoyInstaller(pkg).install(dev, VentoyInstallOptions(secureBoot = secureBoot), dev.sizeBytes)
        return Disk(f, dev, r.detected)
    }

    private fun ExFatVolume.put(path: String, size: Int) {
        mkdirs(path.substringBeforeLast('/').ifEmpty { "/" })
        writeFile(path, ByteArrayInputStream(ByteArray(size) { (it % 251).toByte() }), size.toLong())
    }

    // ------------------------------------------------------------------ catálogo

    @Test
    fun catalogFollowsVentoyRules() {
        val d = ventoyDisk()
        d.dev.use {
            val vol = d.vol()
            vol.put("/Linux/ubuntu.iso", 40_000)
            vol.put("/pequena.iso", 1000)
            vol.put("/Ignorada/x.iso", 40_000); vol.put("/Ignorada/.ventoyignore", 0)
            vol.put("/Ignorada/Sub/y.iso", 40_000)
            vol.put("/.Trashes/lixo.iso", 40_000)
            vol.put("/Win/install.wim", 40_000)
            vol.put("/tool.efi", 40_000)
            vol.put("/notas.txt", 40_000)
            val all = ImageCatalog.scan(vol, ImageCatalog.rulesFrom(null, vol)).associateBy { it.path }
            assertTrue(all.getValue("/Linux/ubuntu.iso").visible)
            assertEquals("menor que 32 KiB", all.getValue("/pequena.iso").hiddenReason)
            assertFalse(all.getValue("/Ignorada/x.iso").visible)
            assertFalse(all.getValue("/Ignorada/Sub/y.iso").visible)
            assertFalse(all.getValue("/.Trashes/lixo.iso").visible)
            assertTrue(all.getValue("/Win/install.wim").hiddenReason!!.contains("wimboot"))
            assertTrue(all.getValue("/tool.efi").visible && all.getValue("/tool.efi").uefiOnly)
            assertFalse("/notas.txt" in all)

            // regras vindas do ventoy.json: pasta de busca, filtro .iso e lista negra
            val store = VentoyConfigStore(vol)
            val cfg = VentoyConfig.parse(
                """{ "control": [ { "VTOY_DEFAULT_SEARCH_ROOT": "/Linux" } ], "image_blacklist": [ "/Linux/ubuntu.iso" ] }""",
            ).first!!
            store.save(cfg)
            val r2 = ImageCatalog.scan(vol, ImageCatalog.rulesFrom(store.load().first, vol)).associateBy { it.path }
            assertTrue(r2.getValue("/Linux/ubuntu.iso").hiddenReason!!.contains("lista negra"))
            assertTrue(r2.getValue("/tool.efi").hiddenReason!!.contains("fora da pasta de busca"))
        }
    }

    @Test
    fun ventoyWildcardMatchesOneCharacter() {
        // no Ventoy, '*' casa exatamente UM caractere (ventoy_strncmp)
        assertTrue(ImageCatalog.fileMatches("/ISO/ubuntu-*.iso", "/ISO/ubuntu-9.iso"))
        assertFalse(ImageCatalog.fileMatches("/ISO/ubuntu-*.iso", "/ISO/ubuntu-24.iso"))
        assertTrue(ImageCatalog.dirMatches("/ISO/Linux/a.iso", "/ISO/"))
    }

    // ------------------------------------------------------------------ ventoy.json

    @Test
    fun configStoreKeepsBackupsAndRefusesSyntaxErrors() {
        val d = ventoyDisk()
        d.dev.use {
            val store = VentoyConfigStore(d.vol())
            assertFalse(store.exists())
            assertEquals(null, store.saveText("""{ "control": [ { "VTOY_MENU_TIMEOUT": "5" } ] }"""))
            Thread.sleep(1100)
            val b1 = store.saveText("""{ "control": [ { "VTOY_MENU_TIMEOUT": "9" } ] }""")
            assertNotNull(b1)
            assertTrue(store.readBackup(b1).contains("\"5\""))
            assertFailsWith<IOException> { store.saveText("{ \"control\": [ ") }
            assertTrue(store.readText()!!.contains("\"9\""), "arquivo com erro não pode ter sido gravado")
            store.restore(b1)
            assertEquals("5", store.load().first!!.control()["VTOY_MENU_TIMEOUT"])
            assertTrue(store.backups().size >= 2)
        }
        fsckData(d)
    }

    // ------------------------------------------------------------------ temas

    @Test
    fun themesInstallImportApplyRestore() {
        val d = ventoyDisk()
        d.dev.use {
            val vol = d.vol()
            val store = VentoyConfigStore(vol)
            val tm = ThemeManager(vol, store)
            val original = tm.installOriginal(pkg)
            assertEquals("/ventoy/theme/theme.txt", original)
            assertEquals(Hashing.hash(File(ventoyDir, "plugin/ventoy/theme/background.png")), Hashing.hash(tm.readImage("/ventoy/theme/background.png")))
            val theme = tm.parse(original)
            assertEquals("10%", theme.find("boot_menu")!!.properties["left"])
            assertEquals("menu_*.png", theme.find("boot_menu")!!.properties["menu_pixmap_style"])
            assertEquals("95%-25", theme.components.filter { it.type == "hbox" }[1].properties["top"])

            // importa um .zip com o theme.txt dentro de uma subpasta
            val zip = ByteArrayOutputStream().also { bos ->
                ZipOutputStream(bos).use { z ->
                    z.putNextEntry(ZipEntry("MeuTema/theme.txt")); z.write("desktop-color: \"#102030\"\n+ boot_menu { left = 20% }\n".toByteArray()); z.closeEntry()
                    z.putNextEntry(ZipEntry("MeuTema/fundo.png")); z.write(File(ventoyDir, "plugin/ventoy/theme/background.png").readBytes()); z.closeEntry()
                }
            }.toByteArray()
            val imported = tm.importZip(ByteArrayInputStream(zip), "Escuro")
            assertEquals("/ventoy/themes/Escuro/theme.txt", imported)
            assertFailsWith<IOException> { tm.importZip(ByteArrayInputStream(zip), "Escuro") }

            tm.apply(listOf(imported))
            assertEquals(listOf(imported), tm.activeThemeFiles())
            assertTrue(tm.discover().single { it.themeFile == imported }.active)
            tm.apply(listOf(original, imported), defaultIndex = 0, random = "boot_day")
            val obj = store.load().first!!.plugin("theme") as JsonObject
            assertEquals(2, (obj["file"] as JsonArray).size)
            assertEquals("boot_day", (obj["random"] as JsonPrimitive).content)

            tm.remove(tm.discover().single { it.themeFile == imported })
            assertEquals(listOf(original), tm.activeThemeFiles())
            tm.restoreDefault()
            assertTrue(tm.activeThemeFiles().isEmpty())
            assertTrue(store.validate(store.load().first!!).none { it.severity == ConfigIssue.Severity.ERROR })
        }
        fsckData(d)
    }

    // ------------------------------------------------------------------ persistência

    @Test
    fun persistenceFileIsValidExt3AndLinked() {
        val d = ventoyDisk(sizeMiB = 768)
        d.dev.use {
            val vol = d.vol()
            vol.put("/Linux/debian-live.iso", 40_000)
            val store = VentoyConfigStore(vol)
            val p = VentoyPersistence.Preset.DEBIAN
            VentoyPersistence.create(
                d.data, vol, store,
                VentoyPersistence.Request("/persistence/debian.dat", 64, ExtFormatter.Type.EXT3, p.label, p.configFile, linkImage = "/Linux/debian-live.iso"),
            )
            val rule = (store.load().first!!.plugin("persistence") as JsonArray).single() as JsonObject
            assertEquals("/persistence/debian.dat", (rule["backend"] as JsonPrimitive).content)
            assertTrue(vol.resolve("/persistence/debian.dat")!!.contiguous)
            // extrai o .dat e valida com e2fsck
            val dat = tmp("dat", ".dat")
            vol.openFile("/persistence/debian.dat").use { input -> dat.outputStream().use { input.copyTo(it) } }
            tool("e2fsck")?.let { run(it, "-fn", dat.path) }
            tool("debugfs")?.let { assertTrue(run(it, "-R", "cat /persistence.conf", dat.path).contains("/ union")) }
            assertFailsWith<IOException> {
                VentoyPersistence.create(d.data, vol, store, VentoyPersistence.Request("/persistence/debian.dat", 8))
            }
        }
        fsckData(d)
    }

    // ------------------------------------------------------------------ Secure Boot + verificação

    @Test
    fun secureBootOffInstallUpdateAndCheck() {
        val d = ventoyDisk(secureBoot = false)
        d.dev.use { dev ->
            assertEquals(false, d.info.secureBootSupport)
            val efi = dev.slice(d.info.part2Start!!, VentoyPackage.EFI_PART_SECTORS)
            val fat = FatVolume.open(efi)
            assertFalse(fat.exists("/EFI/BOOT/mmx64.efi"))
            assertFalse(fat.exists("/ENROLL_THIS_KEY_IN_MOKMANAGER.cer"))
            assertContentEquals(FatVolume.open(MemoryBlockDevice(pkg.openDiskImg().use { it.readBytes() }))
                .readFile("/EFI/BOOT/grubx64_real.efi"), fat.readFile("/EFI/BOOT/BOOTX64.EFI"))
            fsckFat(d)
            val report = VentoyMaintenance.checkInstallation(dev, pkg)
            assertTrue(report.none { it.level == CheckItem.Level.ERROR }, report.joinToString("\n"))

            // atualização mantém desligado; depois liga de novo
            assertEquals(false, VentoyUpdater(pkg).update(dev, dev.sizeBytes).detected.secureBootSupport)
            assertEquals(true, VentoyUpdater(pkg).update(dev, dev.sizeBytes, secureBoot = true).detected.secureBootSupport)
            assertTrue(VentoyMaintenance.checkInstallation(dev, pkg).none { it.level == CheckItem.Level.ERROR })

            // corrompe 1 byte da VTOYEFI → a verificação precisa acusar
            val lba = d.info.part2Start!! + 30000
            val s = dev.readBlocks(lba, 1); s[7] = (s[7] + 1).toByte(); dev.write(lba, s)
            val bad = VentoyMaintenance.checkInstallation(dev, pkg)
            assertTrue(bad.any { it.level == CheckItem.Level.ERROR && it.title.contains("VTOYEFI") })
        }
    }

    // ------------------------------------------------------------------ backup

    @Test
    fun backupAndRestoreVentoyFolder() {
        val d = ventoyDisk()
        d.dev.use {
            val vol = d.vol()
            val store = VentoyConfigStore(vol)
            store.saveText("""{ "control": [ { "VTOY_MENU_TIMEOUT": "7" } ] }""")
            ThemeManager(vol, store).installOriginal(pkg)
            val zip = ByteArrayOutputStream()
            val n = VentoyMaintenance.backup(vol, zip)
            assertTrue(n > 20)
            vol.delete("/ventoy", recursive = true)
            val restored = VentoyMaintenance.restoreBackup(vol, ByteArrayInputStream(zip.toByteArray()), overwrite = false)
            assertEquals(n, restored.size)
            assertEquals("7", store.load().first!!.control()["VTOY_MENU_TIMEOUT"])
        }
        fsckData(d)
    }

    // ------------------------------------------------------------------ atualização online

    @Test
    fun releaseDownloadIsVerifiedResumedAndRejectedWhenTampered() {
        val work = File.createTempFile("rel", "").also { it.delete(); it.mkdirs(); temp += it }
        // monta um ventoy-1.1.18-linux.tar.gz com os arquivos oficiais (mesma estrutura do pacote Linux)
        val stage = File(work, "stage/ventoy-1.1.18").apply { mkdirs() }
        listOf("boot/boot.img", "boot/core.img.xz", "ventoy/ventoy.disk.img.xz", "ventoy/version").forEach { rel ->
            File(ventoyDir, rel).copyTo(File(stage, rel))
        }
        File(ventoyDir, "plugin").copyRecursively(File(stage, "plugin"))
        val tgz = File(work, "ventoy-1.1.18-linux.tar.gz")
        run("tar", "-czf", tgz.path, "-C", File(work, "stage").path, "ventoy-1.1.18")
        var shaText = "${Hashing.hash(tgz)}  ventoy-1.1.18-linux.tar.gz\n"
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"
        server.createContext("/api/releases") { ex ->
            val body = """[{"tag_name":"v1.1.18","name":"1.1.18 release","published_at":"2026-10-09T00:00:00Z","prerelease":false,
                "assets":[{"name":"ventoy-1.1.18-linux.tar.gz","browser_download_url":"$base/dl/pkg","size":${tgz.length()}},
                          {"name":"sha256.txt","browser_download_url":"$base/dl/sha","size":100}]}]""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        server.createContext("/dl/pkg") { ex ->
            val all = tgz.readBytes()
            val range = ex.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull()
            val body = if (range != null) all.copyOfRange(range, all.size) else all
            ex.sendResponseHeaders(if (range != null) 206 else 200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        server.createContext("/dl/sha") { ex -> val b = shaText.toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
        server.start()
        try {
            val client = VentoyReleaseClient(apiBase = "$base/api", allowHttp = true)
            val rel = client.latestStable()!!
            assertEquals("1.1.18", rel.version)
            // simula download interrompido: metade do arquivo já existe como .part
            val dest = File(work, "downloads")
            File(dest, "ventoy-1.1.18").mkdirs()
            File(dest, "ventoy-1.1.18/ventoy-1.1.18-linux.tar.gz.part").writeBytes(tgz.readBytes().copyOf((tgz.length() / 2).toInt()))
            val dl = client.download(rel, dest)
            assertTrue(dl.verified)
            assertTrue(dl.pkg.verifyIntegrity().isEmpty())
            assertEquals("1.1.18", dl.pkg.version)
            assertTrue(dl.pkg.listFiles("plugin/ventoy/theme").any { it.endsWith("theme.txt") })
            assertEquals(1, client.downloaded(dest).size)
            // instala usando o pacote baixado
            val f = tmp("dl")
            FileBlockDevice.create(f, 256L shl 20).use { dev -> VentoyInstaller(dl.pkg).install(dev, VentoyInstallOptions(), dev.sizeBytes) }

            // checksum adulterado → download descartado
            shaText = "${"0".repeat(64)}  ventoy-1.1.18-linux.tar.gz\n"
            assertFailsWith<IOException> { client.download(rel, File(work, "downloads2")) }
        } finally {
            server.stop(0)
        }
    }

    // ------------------------------------------------------------------ utilitários

    private fun tool(name: String) = listOf("/usr/sbin/$name", "/sbin/$name", "/usr/bin/$name").firstOrNull { File(it).exists() }

    private fun run(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(180, TimeUnit.SECONDS)
        assertEquals(0, p.exitValue(), "${cmd.joinToString(" ")} falhou:\n$out")
        return out
    }

    private fun extract(d: Disk, start: Long, count: Long): File {
        val out = tmp("part")
        FileBlockDevice.create(out, count * 512).use { o ->
            FileBlockDevice.open(d.file, readOnly = true).use { src ->
                val buf = ByteArray(1 shl 20)
                var done = 0L
                while (done < count) {
                    val n = minOf(2048L, count - done).toInt()
                    src.read(start + done, buf, 0, n * 512); o.write(done, buf, 0, n * 512); done += n
                }
            }
        }
        return out
    }

    private fun fsckData(d: Disk) {
        val fsck = tool("fsck.exfat") ?: return
        run(fsck, "-n", extract(d, d.info.part1Start!!, d.info.part1Sectors!!).path)
    }

    private fun fsckFat(d: Disk) {
        val fsck = tool("fsck.fat") ?: return
        run(fsck, "-n", extract(d, d.info.part2Start!!, VentoyPackage.EFI_PART_SECTORS).path)
    }
}
