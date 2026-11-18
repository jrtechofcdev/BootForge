package dev.bootforge.fs.ext

import dev.bootforge.blockdev.FileBlockDevice
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Valida o formatador com as ferramentas oficiais do e2fsprogs (e2fsck, debugfs), quando instaladas. */
class ExtFormatterTest {

    private fun tool(name: String) = listOf("/usr/sbin/$name", "/sbin/$name").map(::File).firstOrNull { it.exists() }?.path

    private fun run(vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(120, TimeUnit.SECONDS)
        return p.exitValue() to out
    }

    private fun check(sizeMiB: Long, type: ExtFormatter.Type, config: Pair<String, String>?) {
        val f = File.createTempFile("persist", ".dat")
        try {
            val r = FileBlockDevice.create(f, sizeMiB shl 20).use { ExtFormatter.format(it, ExtFormatter.Options(type, "casper-rw", config)) }
            assertTrue(r.groups >= 1)
            val e2fsck = tool("e2fsck") ?: return
            val (code, out) = run(e2fsck, "-fn", f.path)
            assertEquals(0, code, "e2fsck reprovou ($sizeMiB MiB, $type):\n$out")
            val debugfs = tool("debugfs") ?: return
            val (_, info) = run(debugfs, "-R", "stats", f.path)
            assertTrue(info.contains("Filesystem volume name:   casper-rw"), info)
            if (type == ExtFormatter.Type.EXT3) assertTrue(info.contains("has_journal"), info)
            if (config != null) {
                val (_, cat) = run(debugfs, "-R", "cat /${config.first}", f.path)
                assertTrue(cat.contains(config.second.trim()), cat)
            }
        } finally {
            f.delete()
        }
    }

    @Test fun ext2Small() = check(8, ExtFormatter.Type.EXT2, null)
    @Test fun ext3Small() = check(64, ExtFormatter.Type.EXT3, null)
    @Test fun ext3WithPersistenceConf() = check(300, ExtFormatter.Type.EXT3, "persistence.conf" to "/ union\n")
    @Test fun ext3Large4kBlocks() = check(1024, ExtFormatter.Type.EXT3, null)
    @Test fun ext2OddSize() = check(777, ExtFormatter.Type.EXT2, null)
}
