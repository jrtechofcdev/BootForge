package dev.bootforge.isoedit

import dev.bootforge.blockdev.RandomReader
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Editor de ISO de ponta a ponta. As ISOs de entrada são geradas por ferramentas independentes
 * (grub-mkrescue/xorriso, genisoimage -udf) e as de saída são conferidas pelo validador do
 * BootForge E por isovfy, xorriso, osirrox, sgdisk e udfinfo. Sem as ferramentas, o teste é pulado.
 */
class IsoEditorTest {

    private fun tool(n: String) = listOf("/usr/bin/$n", "/usr/sbin/$n", "/sbin/$n").firstOrNull { File(it).exists() }

    private fun run(vararg cmd: String, ok: Boolean = true): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(300, TimeUnit.SECONDS)
        if (ok) assertEquals(0, p.exitValue(), "${cmd.joinToString(" ")}\n$out")
        return out
    }

    private fun tempDir() = File.createTempFile("bf-isoed", "").apply { delete(); mkdirs() }

    /** ISO híbrida BIOS+UEFI feita pelo grub-mkrescue (MBR GRUB2, GPT, APM, El Torito). */
    private fun grubIso(work: File): File? {
        val mk = tool("grub-mkrescue") ?: return null
        if (tool("xorriso") == null) return null
        val t = File(work, "gtree")
        File(t, "boot/grub").mkdirs()
        File(t, "boot/grub/grub.cfg").writeText("set timeout=-1\nmenuentry \"Original\" { halt }\n")
        File(t, "docs/LEIAME.txt").apply { parentFile.mkdirs(); writeText("documento original") }
        File(t, "docs/apagar.bin").writeBytes(Random(1).nextBytes(100_000))
        File(t, "dados/grande.bin").apply { parentFile.mkdirs(); writeBytes(Random(2).nextBytes(3_000_000)) }
        val iso = File(work, "grub.iso")
        run(mk, "-o", iso.path, t.path)
        return iso
    }

    private class Built(val iso: File, val plan: BuildPlan, val report: ValidationReport, val session: EditSession)

    private fun rebuild(input: File, out: File, mode: RebuildMode, edits: EditSession.() -> Unit): Built {
        RandomReader.of(input).use { r ->
            val s = EditSession(IsoSource.open(r))
            s.edits()
            val plan = IsoBuilder.plan(s, mode)
            val sha = out.outputStream().buffered().use { plan.write(it) }
            val report = RandomReader.of(out).use { o -> IsoValidator.validate(plan, s, o, sha) }
            return Built(out, plan, report, s)
        }
    }

    private fun assertValid(b: Built) {
        assertTrue(b.report.ok, b.report.failures.joinToString("\n") { "${it.title}: ${it.detail}" })
        tool("isovfy")?.let { assertTrue(run(it, b.iso.path, ok = false).contains("No errors found"), "isovfy") }
    }

    private fun extract(iso: File, path: String, work: File): ByteArray {
        val f = File(work, "x-" + path.replace('/', '_')); f.delete()
        run(tool("osirrox")!!, "-indev", iso.path, "-extract", path, f.path)
        return f.readBytes()
    }

    @Test
    fun preserveModeKeepsHybridBootAndAppliesEdits() {
        val work = tempDir()
        try {
            val iso = grubIso(work) ?: return
            val b = rebuild(iso, File(work, "out.iso"), RebuildMode.PRESERVE) {
                replaceText("/boot/grub/grub.cfg", "set timeout=-1\nmenuentry \"Editado pelo BootForge\" { halt }\n")
                mkdir("/", "Novidades")
                addFile("/Novidades", "Arquivo com acentuação e nome longo ✓.txt", BytesContent("conteúdo novo".toByteArray(), "teste"))
                rename("/docs/LEIAME.txt", "LEIA-ME primeiro.txt")
                delete("/docs/apagar.bin")
                move("/dados/grande.bin", "/Novidades")
            }
            assertValid(b)
            assertEquals(BootStatus.VERIFIED, b.report.boot)
            assertTrue(b.plan.hybrid)
            // xorriso enxerga o mesmo El Torito (mesmos setores) e a área de sistema híbrida
            val before = run(tool("xorriso")!!, "-indev", iso.path, "-report_el_torito", "plain")
            val after = run(tool("xorriso")!!, "-indev", b.iso.path, "-report_el_torito", "plain", "-report_system_area", "plain")
            fun boots(s: String) = s.lines().filter { it.startsWith("El Torito boot img") || it.startsWith("El Torito img opts") }
            assertEquals(boots(before), boots(after))
            assertTrue(after.contains("GPT"), after)
            tool("sgdisk")?.let { assertTrue(run(it, "-v", b.iso.path, ok = false).contains("No problems found")) }
            // conteúdo editado lido por uma implementação independente (osirrox)
            assertEquals("set timeout=-1\nmenuentry \"Editado pelo BootForge\" { halt }\n", String(extract(b.iso, "/boot/grub/grub.cfg", work)))
            assertEquals("conteúdo novo", String(extract(b.iso, "/Novidades/Arquivo com acentuação e nome longo ✓.txt", work)))
            assertEquals("documento original", String(extract(b.iso, "/docs/LEIA-ME primeiro.txt", work)))
            assertContentEquals(Random(2).nextBytes(3_000_000), extract(b.iso, "/Novidades/grande.bin", work))
            val ls = run(tool("osirrox")!!, "-indev", b.iso.path, "-ls", "/docs")
            assertFalse(ls.contains("apagar.bin"), ls)
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun replacedBootImageIsRelocatedAndPatched() {
        val work = tempDir()
        try {
            val iso = grubIso(work) ?: return
            // troca o eltorito.img pelo próprio conteúdo: força a mudança de lugar sem quebrar o boot
            val img = File(work, "eltorito.img").apply { writeBytes(extract(iso, "/boot/grub/i386-pc/eltorito.img", work)) }
            val b = rebuild(iso, File(work, "out.iso"), RebuildMode.PRESERVE) { replace("/boot/grub/i386-pc/eltorito.img", FileContent(img)) }
            assertValid(b)
            assertEquals(BootStatus.VERIFIED, b.report.boot)
            assertTrue(b.plan.warnings.any { it.contains("eltorito.img") })
            val rep = run(tool("xorriso")!!, "-indev", b.iso.path, "-report_el_torito", "plain")
            val line = rep.lines().first { it.startsWith("El Torito boot img") && it.contains("BIOS") }
            val origLine = run(tool("xorriso")!!, "-indev", iso.path, "-report_el_torito", "plain").lines().first { it.startsWith("El Torito boot img") && it.contains("BIOS") }
            assertTrue(line != origLine, "o LBA deveria ter mudado:\n$origLine\n$line")
            assertTrue(rep.contains("boot-info-table grub2-boot-info"), rep)
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun fullRebuildRecreatesElToritoAndCompacts() {
        val work = tempDir()
        try {
            val iso = grubIso(work) ?: return
            val b = rebuild(iso, File(work, "full.iso"), RebuildMode.FULL) {
                delete("/dados/grande.bin")
                replaceText("/boot/grub/grub.cfg", "set timeout=-1\nmenuentry \"Reconstruida\" { halt }\n")
            }
            assertValid(b)
            assertEquals(BootStatus.VERIFIED, b.report.boot)
            assertFalse(b.plan.hybrid)
            assertTrue(b.plan.warnings.any { it.contains("híbrida") })
            assertTrue(b.iso.length() < iso.length() - 2_000_000, "deveria ficar menor sem o arquivo apagado")
            val rep = run(tool("xorriso")!!, "-indev", b.iso.path, "-report_el_torito", "plain")
            assertTrue(rep.contains("BIOS") && rep.contains("UEFI") && rep.contains("boot-info-table grub2-boot-info"), rep)
            assertEquals("set timeout=-1\nmenuentry \"Reconstruida\" { halt }\n", String(extract(b.iso, "/boot/grub/grub.cfg", work)))
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun udfImageIsRebuiltWithUdfAndElTorito() {
        val geniso = tool("genisoimage") ?: return
        val work = tempDir()
        try {
            // boot images reais extraídas de uma ISO do grub-mkrescue
            val g = grubIso(work) ?: return
            val t = File(work, "wtree")
            File(t, "boot").mkdirs()
            File(t, "boot/eltorito.img").writeBytes(extract(g, "/boot/grub/i386-pc/eltorito.img", work))
            File(t, "efi/microsoft/boot").mkdirs()
            File(t, "efi/microsoft/boot/efisys.bin").writeBytes(extract(g, "/efi.img", work))
            File(t, "sources").mkdirs()
            File(t, "sources/install.wim").writeBytes(Random(7).nextBytes(2_500_000))
            File(t, "bootmgr").writeText("bootmgr")
            File(t, "Pasta ✓ ação/Arquivo Unicode ✓.txt").apply { parentFile.mkdirs(); writeText("olá") }
            val iso = File(work, "win.iso")
            run(geniso, "-quiet", "-udf", "-J", "-joliet-long", "-iso-level", "3", "-input-charset", "utf-8", "-V", "CCCOMA_X64FRE_PT-BR_DV9",
                "-b", "boot/eltorito.img", "-no-emul-boot", "-boot-load-size", "4", "-boot-info-table",
                "-eltorito-alt-boot", "-e", "efi/microsoft/boot/efisys.bin", "-no-emul-boot", "-o", iso.path, t.path)
            RandomReader.of(iso).use { assertEquals(TreeKind.UDF, IsoSource.open(it).treeKind) }
            assertEquals(RebuildMode.FULL, RandomReader.of(iso).use { IsoBuilder.recommended(IsoSource.open(it)) })
            assertFailsWith<java.io.IOException> {
                RandomReader.of(iso).use { IsoBuilder.plan(EditSession(IsoSource.open(it)), RebuildMode.PRESERVE) }
            }

            val b = rebuild(iso, File(work, "win-new.iso"), RebuildMode.FULL) {
                addFile("/sources", "ei.cfg", BytesContent("[Channel]\r\nRetail\r\n".toByteArray(), "ei.cfg"))
            }
            assertValid(b)
            assertEquals(BootStatus.VERIFIED, b.report.boot)
            tool("udfinfo")?.let { u ->
                val info = run(u, b.iso.path)
                assertTrue(info.contains("udfrev=1.02") && info.contains("integrity=closed") && info.contains("label=CCCOMA_X64FRE_PT-BR_DV9"), info)
                assertTrue(Regex("numfiles=(\\d+)").find(info)!!.groupValues[1].toInt() >= 6, info)
            }
            // a árvore UDF relida tem o arquivo novo e os nomes Unicode
            RandomReader.of(b.iso).use { r ->
                val out = IsoSource.open(r)
                assertEquals(TreeKind.UDF, out.treeKind)
                val f = Paths.find(out.root, "/sources/ei.cfg") as VFile
                assertEquals("[Channel]\r\nRetail\r\n", out.openOriginal(f.source as Source.Original, f.size).use { String(it.readBytes()) })
                assertTrue(Paths.find(out.root, "/Pasta ✓ ação/Arquivo Unicode ✓.txt") is VFile)
            }
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun sessionSupportsUndoRedoAndPendingChanges() {
        val work = tempDir()
        try {
            val iso = grubIso(work) ?: return
            RandomReader.of(iso).use { r ->
                val s = EditSession(IsoSource.open(r), File(work, "trabalho"))
                assertFalse(s.isModified)
                s.mkdir("/", "nova")
                s.replaceText("/boot/grub/grub.cfg", "x")
                s.delete("/docs/apagar.bin")
                assertEquals(3, s.history.size)
                assertEquals(setOf(PendingChange.Kind.ADDED, PendingChange.Kind.MODIFIED, PendingChange.Kind.REMOVED), s.pendingChanges().map { it.kind }.toSet())
                assertEquals("Excluir arquivo /docs/apagar.bin", s.undo())
                assertTrue(Paths.find(s.root, "/docs/apagar.bin") is VFile)
                assertTrue(s.canRedo)
                s.redo()
                assertTrue(Paths.find(s.root, "/docs/apagar.bin") == null)
                s.undo(); s.undo()
                assertEquals(String(File(work, "gtree/boot/grub/grub.cfg").readBytes()), s.open(Paths.find(s.root, "/boot/grub/grub.cfg") as VFile).use { String(it.readBytes()) })
                s.rename("/docs", "Documentos") // nova operação descarta o "refazer"
                assertFalse(s.canRedo)
                assertFailsWith<IllegalArgumentException> { s.mkdir("/", "nova") } // já existe
                assertFailsWith<IllegalArgumentException> { s.rename("/Documentos/LEIAME.txt", "a/b") }
                assertFailsWith<IllegalArgumentException> { s.move("/Documentos", "/Documentos") }
                s.discardAll()
                assertFalse(s.isModified)
                assertTrue(s.pendingChanges().isEmpty())
                // extração do que está na sessão (com as edições)
                s.replaceText("/docs/LEIAME.txt", "editado")
                val dir = File(work, "extraido")
                val res = IsoExtractor.extract(s, listOf("/docs"), DirectorySink(dir))
                assertEquals(2, res.files)
                assertEquals("editado", File(dir, "docs/LEIAME.txt").readText())
            }
        } finally {
            work.deleteRecursively()
        }
    }

    /** ISO real do Alpine Linux (opcional): defina BOOTFORGE_ALPINE_ISO para rodar. */
    @Test
    fun realAlpineIsoInBothModes() {
        val path = System.getenv("BOOTFORGE_ALPINE_ISO")?.takeIf { it.isNotBlank() } ?: return
        val work = tempDir()
        try {
            for (mode in RebuildMode.entries) {
                val b = rebuild(File(path), File(work, "alpine-$mode.iso"), mode) {
                    replaceText("/boot/grub/grub.cfg", "set timeout=1\nmenuentry \"BootForge\" {\nlinux /boot/vmlinuz-virt modules=loop,squashfs,sd-mod,usb-storage\ninitrd /boot/initramfs-virt\n}\n")
                    addFile("/", "bootforge.txt", BytesContent("ok".toByteArray(), "teste"))
                }
                assertValid(b)
                assertEquals(BootStatus.VERIFIED, b.report.boot)
            }
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun namesAndExtentsFollowIsoRules() {
        val used = mutableSetOf<String>()
        assertEquals("LEIA_ME_PRIMEIRO.TXT;1", IsoNames.isoName("leia-me primeiro.txt", false, used))
        assertEquals("LEIA_ME_PRIMEIRO_1.TXT;1", IsoNames.isoName("Leia me primeiro.txt", false, used))
        assertEquals("BOOT", IsoNames.isoName("boot", true, mutableSetOf()))
        assertEquals("SEM_EXTENSAO.;1", IsoNames.isoName("sem_extensao", false, mutableSetOf()))
        assertTrue(IsoNames.compareIso("A.B;1", "AB;1") < 0)
        val parts = IsoBuilder.contiguousExtents(100, 5L shl 30)
        assertEquals(2, parts.size)
        assertEquals(5L shl 30, parts.sumOf { it.second })
        assertEquals(100 + MAX_EXTENT / 2048, parts[1].first)
    }
}
