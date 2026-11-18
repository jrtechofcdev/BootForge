package dev.bootforge.isoedit

import dev.bootforge.blockdev.RandomReader
import java.io.File
import kotlin.test.Test

class ExploreTest {
    @Test
    fun explore() {
        val iso = System.getenv("BF_ISO")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        val out = File(System.getenv("BF_OUT"))
        val mode = RebuildMode.valueOf(System.getenv("BF_MODE")?.takeIf { it.isNotBlank() } ?: "PRESERVE")
        RandomReader.of(iso).use { r ->
            val src = IsoSource.open(r)
            println("tipo=${src.treeKind} fmt=${src.formatLabel} files=${Paths.countFiles(src.root)} dirs=${Paths.countDirs(src.root)} blockers=${src.blockers} warn=${src.warnings}")
            src.bootImages.forEach { println("boot ${it.entry.platformName} lba=${it.entry.lba} cnt=${it.entry.sectorCount} path=${it.path} size=${it.size} bit=${it.bootInfoTable} g2=${it.grub2BootInfo}") }
            val s = EditSession(src)
            System.getenv("BF_EDITS")?.split(";")?.filter { it.isNotBlank() }?.forEach { cmd ->
                val p = cmd.split("|")
                when (p[0]) {
                    "text" -> s.replaceText(p[1], p[2].replace("\\n", "\n"))
                    "add" -> s.addFile(p[1], p[2], BytesContent(p[3].toByteArray(), "teste"))
                    "addfile" -> s.addFile(p[1], p[2], FileContent(File(p[3])))
                    "ren" -> s.rename(p[1], p[2])
                    "del" -> s.delete(p[1])
                    "replace" -> s.replace(p[1], FileContent(File(p[2])))
                    "mkdir" -> s.mkdir(p[1], p[2])
                    "mv" -> s.move(p[1], p[2])
                }
            }
            s.pendingChanges().forEach { println("pend $it") }
            val plan = IsoBuilder.plan(s, mode)
            println("total=${plan.totalBytes} boot=${plan.bootSummary}")
            plan.warnings.forEach { println("aviso: $it") }
            val sha = out.outputStream().buffered().use { plan.write(it) }
            println("sha=$sha size=${out.length()}")
        }
    }
}
