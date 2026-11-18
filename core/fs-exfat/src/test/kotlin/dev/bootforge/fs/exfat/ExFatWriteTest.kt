package dev.bootforge.fs.exfat

import dev.bootforge.blockdev.BlockDevice
import dev.bootforge.blockdev.FileBlockDevice
import dev.bootforge.blockdev.readBlocks
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExFatWriteTest {

    private val files = mutableListOf<File>()

    @AfterTest
    fun cleanup() = files.forEach { it.delete() }

    /** Volume exFAT novo, com cluster pequeno para forçar crescimento de pastas e fragmentação. */
    private fun newVolume(sizeMiB: Long = 64, cluster: Int = 4096): Pair<File, FileBlockDevice> {
        val f = File.createTempFile("exfatw", ".img").also { files += it }
        val dev = FileBlockDevice.create(f, sizeMiB shl 20)
        ExFatFormatter.format(dev, ExFatFormatter.Options("TESTE", cluster, partitionOffsetLba = 0))
        return f to dev
    }

    private fun ExFatVolume.put(path: String, data: ByteArray, overwrite: Boolean = false) =
        writeFile(path, ByteArrayInputStream(data), data.size.toLong(), overwrite)

    private fun ExFatVolume.get(path: String) = openFile(path).use { it.readBytes() }

    @Test
    fun writeAndReadBackVariousSizes() {
        val (f, dev) = newVolume()
        dev.use {
            val vol = ExFatVolume.open(dev, writable = true)
            vol.mkdir("/ISO")
            val sizes = listOf(0, 1, 4095, 4096, 4097, 3 * 1048576 + 7)
            val rnd = Random(1)
            val contents = sizes.associateWith { rnd.nextBytes(it) }
            contents.forEach { (n, d) -> vol.put("/ISO/arquivo-$n.bin", d) }
            // reabre do zero: tudo precisa estar realmente no disco
            val again = ExFatVolume.open(dev)
            contents.forEach { (n, d) -> assertContentEquals(d, again.get("/ISO/arquivo-$n.bin"), "tamanho $n") }
            assertEquals(sizes.size, again.list("/ISO").size)
            assertEquals(0, dev.readBlocks(0, 1)[106].toInt() and 0x02, "VolumeDirty deve ser desligado")
            assertTrue(again.extents("/ISO/arquivo-${3 * 1048576 + 7}.bin").size == 1, "arquivo grande deve ser contíguo")
        }
        fsck(f)
    }

    @Test
    fun namesAreValidatedAndCaseInsensitive() {
        val (_, dev) = newVolume()
        dev.use {
            val vol = ExFatVolume.open(dev, writable = true)
            vol.put("/Ubuntu Linux.ISO", byteArrayOf(1))
            assertNotNull(vol.resolve("/ubuntu linux.iso"))
            assertFailsWith<IOException> { vol.put("/UBUNTU LINUX.iso", byteArrayOf(2)) }
            listOf("a:b", "x?y", "barra\\", "aspas\"", "fim.", "").forEach { bad ->
                assertFailsWith<IOException>(bad) { vol.put("/$bad", byteArrayOf(1)) }
            }
            vol.put("/Ação ç ñ 日本 😀.txt", "ok".toByteArray())
            assertEquals("ok", String(ExFatVolume.open(dev).get("/ação Ç Ñ 日本 😀.TXT")))
        }
    }

    @Test
    fun directoriesGrowBeyondOneCluster() {
        val (f, dev) = newVolume()
        dev.use {
            val vol = ExFatVolume.open(dev, writable = true)
            vol.mkdir("/muitos")
            repeat(600) { vol.put("/muitos/arquivo-com-nome-longo-numero-$it.txt", "conteúdo $it".toByteArray()) }
            repeat(200) { vol.put("/raiz-$it.txt", byteArrayOf(it.toByte())) }
            val again = ExFatVolume.open(dev)
            assertEquals(600, again.list("/muitos").size)
            assertEquals(201, again.list("/").size)
            assertEquals("conteúdo 599", String(again.get("/muitos/arquivo-com-nome-longo-numero-599.txt")))
            assertTrue(again.resolve("/muitos")!!.size > again.clusterSize)
        }
        fsck(f)
    }

    @Test
    fun fragmentedFileIsChainedAndReadable() {
        val (f, dev) = newVolume(sizeMiB = 16)
        dev.use {
            val vol = ExFatVolume.open(dev, writable = true)
            // enche o volume de arquivos de 1 cluster e apaga 1 a cada 2 → só sobram buracos de 1 cluster
            var i = 0
            while (vol.freeClusters() > 4) vol.put("/p$i", ByteArray(4096) { i.toByte() }).also { i++ }
            for (k in 0 until i step 2) vol.delete("/p$k")
            val big = Random(7).nextBytes(40 * 4096 + 123)
            val r = vol.put("/fragmentado.bin", big)
            assertTrue(r.fragments > 1)
            val again = ExFatVolume.open(dev)
            assertContentEquals(big, again.get("/fragmentado.bin"))
            assertFalse(again.resolve("/fragmentado.bin")!!.contiguous)
        }
        fsck(f)
    }

    @Test
    fun requireContiguousFailsWithoutFreeRun() {
        val (_, dev) = newVolume(sizeMiB = 16)
        dev.use {
            val vol = ExFatVolume.open(dev, writable = true)
            var i = 0
            while (vol.freeClusters() > 4) vol.put("/p$i", ByteArray(4096)).also { i++ }
            for (k in 0 until i step 2) vol.delete("/p$k")
            val free = vol.freeClusters()
            assertFailsWith<IOException> {
                vol.writeFile("/persist.dat", ByteArrayInputStream(ByteArray(8 * 4096)), 8L * 4096, requireContiguous = true)
            }
            assertEquals(free, vol.freeClusters(), "nada pode ficar reservado após a falha")
            assertNull(vol.resolve("/persist.dat"))
        }
    }

    @Test
    fun renameMoveCopyDeleteOverwrite() {
        val (f, dev) = newVolume()
        dev.use {
            val vol = ExFatVolume.open(dev, writable = true)
            vol.mkdirs("/Linux/Ubuntu")
            vol.mkdir("/Windows")
            val iso = Random(3).nextBytes(200_000)
            vol.put("/Linux/Ubuntu/u.iso", iso)
            // renomeia para nome bem maior (conjunto de entradas cresce e muda de lugar)
            vol.rename("/Linux/Ubuntu/u.iso", "ubuntu-24.04.1-desktop-amd64-versao-com-nome-muito-comprido.iso")
            val renamed = "/Linux/Ubuntu/ubuntu-24.04.1-desktop-amd64-versao-com-nome-muito-comprido.iso"
            assertContentEquals(iso, vol.get(renamed))
            assertNull(vol.resolve("/Linux/Ubuntu/u.iso"))
            // move entre pastas sem copiar dados
            val clustersBefore = vol.freeClusters()
            vol.move(renamed, "/Windows")
            assertEquals(clustersBefore, vol.freeClusters())
            assertContentEquals(iso, vol.get("/Windows/ubuntu-24.04.1-desktop-amd64-versao-com-nome-muito-comprido.iso"))
            // pasta para dentro dela mesma: proibido
            assertFailsWith<IOException> { vol.move("/Linux", "/Linux/Ubuntu") }
            // copia pasta inteira
            vol.put("/Linux/Ubuntu/notas.txt", "abc".toByteArray())
            vol.copy("/Linux", "/Windows")
            assertEquals("abc", String(vol.get("/Windows/Linux/Ubuntu/notas.txt")))
            // sobrescrever mantém um único arquivo e libera o espaço antigo
            vol.put("/Linux/Ubuntu/notas.txt", ByteArray(50_000) { 9 }, overwrite = true)
            assertEquals(50_000, vol.get("/Linux/Ubuntu/notas.txt").size)
            assertEquals(1, vol.list("/Linux/Ubuntu").size)
            // excluir pasta não vazia exige recursive
            assertFailsWith<IOException> { vol.delete("/Windows") }
            vol.delete("/Windows", recursive = true)
            assertNull(vol.resolve("/Windows"))
        }
        fsck(f)
    }

    /** Pastas criadas por outros sistemas (ex.: Windows) costumam ser contíguas (NoFatChain). */
    @Test
    fun contiguousDirectoryFromOtherFormatterIsConvertedWhenGrowing() {
        val (f, dev) = newVolume()
        dev.use {
            val vol = ExFatVolume.open(dev, writable = true)
            vol.mkdir("/Ferramentas")
            vol.put("/bloqueio", ByteArray(4096)) // ocupa o cluster seguinte → força conversão em cadeia
            patchNoFatChain(dev, vol.resolve("/Ferramentas")!!)
            val v2 = ExFatVolume.open(dev, writable = true)
            assertTrue(v2.resolve("/Ferramentas")!!.contiguous)
            repeat(300) { v2.put("/Ferramentas/f$it.txt", byteArrayOf(it.toByte())) }
            val v3 = ExFatVolume.open(dev)
            assertEquals(300, v3.list("/Ferramentas").size)
            assertFalse(v3.resolve("/Ferramentas")!!.contiguous)
        }
        fsck(f)
    }

    /** Teste aleatório: 400 operações comparadas com um "modelo" em memória. */
    @Test
    fun randomOperationsMatchModel() {
        val (f, dev) = newVolume(sizeMiB = 32)
        val rnd = Random(2026)
        val model = sortedMapOf<String, ByteArray?>() // null = pasta
        dev.use {
            val vol = ExFatVolume.open(dev, writable = true)
            fun dirs() = listOf("/") + model.filterValues { it == null }.keys
            fun filesIn() = model.filterValues { it != null }.keys.toList()
            repeat(400) { step ->
                when (rnd.nextInt(10)) {
                    0, 1, 2, 3 -> { // criar arquivo
                        val d = dirs().random(rnd)
                        val p = d.trimEnd('/') + "/f$step-${"x".repeat(rnd.nextInt(40))}"
                        val data = rnd.nextBytes(rnd.nextInt(0, 60_000))
                        vol.put(p, data); model[p] = data
                    }
                    4 -> { // pasta
                        val d = dirs().random(rnd)
                        val p = d.trimEnd('/') + "/d$step"
                        vol.mkdir(p); model[p] = null
                    }
                    5 -> filesIn().randomOrNull(rnd)?.let { p -> vol.delete(p); model.remove(p) }
                    6 -> filesIn().randomOrNull(rnd)?.let { p -> // renomear
                        val np = p.substringBeforeLast('/') + "/r$step-${"y".repeat(rnd.nextInt(50))}"
                        vol.rename(p, np.substringAfterLast('/')); model[np] = model.remove(p)
                    }
                    7 -> filesIn().randomOrNull(rnd)?.let { p -> // mover
                        val d = dirs().random(rnd)
                        val np = d.trimEnd('/') + "/" + p.substringAfterLast('/')
                        if (np != p && !model.containsKey(np)) { vol.move(p, d); model[np] = model.remove(p) }
                    }
                    8 -> filesIn().randomOrNull(rnd)?.let { p -> // sobrescrever
                        val data = rnd.nextBytes(rnd.nextInt(0, 90_000))
                        vol.put(p, data, overwrite = true); model[p] = data
                    }
                    9 -> model.filterValues { it == null }.keys.randomOrNull(rnd)?.let { d -> // apagar pasta inteira
                        vol.delete(d, recursive = true)
                        model.keys.filter { it == d || it.startsWith("$d/") }.forEach { model.remove(it) }
                    }
                }
            }
            val fresh = ExFatVolume.open(dev)
            val found = sortedMapOf<String, ByteArray?>()
            fresh.walk { path, e -> found[path] = if (e.isDirectory) null else fresh.get(path) }
            assertEquals(model.keys, found.keys)
            model.forEach { (k, v) -> if (v != null) assertContentEquals(v, found[k], k) }
        }
        fsck(f)
    }

    @Test
    fun worksOnVolumeCreatedByMkfsExfat() {
        val mkfs = listOf("/usr/sbin/mkfs.exfat", "/sbin/mkfs.exfat").map(::File).firstOrNull { it.exists() } ?: return
        val f = File.createTempFile("mkfs", ".img").also { files += it }
        FileBlockDevice.create(f, 64L shl 20).close()
        run(mkfs.path, "-c", "32K", f.path)
        FileBlockDevice.open(f).use { dev ->
            val vol = ExFatVolume.open(dev, writable = true)
            vol.mkdirs("/ventoy/theme")
            vol.put("/ventoy/ventoy.json", "{}".toByteArray())
            vol.put("/Linux.iso", Random(9).nextBytes(5_000_000))
        }
        fsck(f)
    }

    // ------------------------------------------------------------------ utilitários

    /** Liga NoFatChain na entrada (simula pasta criada pelo Windows) e recalcula o checksum. */
    private fun patchNoFatChain(dev: BlockDevice, e: ExFatEntry) {
        val boot = dev.readBlocks(0, 1)
        val heap = ExFatEntries.u32(boot, 88)
        val spc = 1 shl boot[109].toInt()
        val root = ExFatEntries.u32(boot, 96)
        val loc = e.location!!
        require(loc.parent.entry == null) { "somente pastas na raiz" }
        val lba = heap + (root - 2) * spc
        val cluster = dev.readBlocks(lba, spc)
        val set = cluster.copyOfRange(loc.offset, loc.offset + loc.count * 32)
        set[33] = (set[33].toInt() or ExFatEntries.FLAG_NO_FAT_CHAIN).toByte()
        ExFatEntries.put16(set, 2, ExFatEntries.setChecksum(set))
        set.copyInto(cluster, loc.offset)
        dev.write(lba, cluster)
    }

    private fun fsck(f: File) {
        val fsck = listOf("/usr/sbin/fsck.exfat", "/sbin/fsck.exfat").map(::File).firstOrNull { it.exists() } ?: return
        run(fsck.path, "-n", f.path)
    }

    private fun run(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(120, TimeUnit.SECONDS)
        assertEquals(0, p.exitValue(), "${cmd.joinToString(" ")} falhou:\n$out")
        return out
    }
}
