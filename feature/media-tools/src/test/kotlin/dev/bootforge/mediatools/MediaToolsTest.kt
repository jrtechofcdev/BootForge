package dev.bootforge.mediatools

import dev.bootforge.blockdev.BlockDevice
import dev.bootforge.blockdev.FaultInjectingBlockDevice
import dev.bootforge.blockdev.FileBlockDevice
import dev.bootforge.blockdev.MemoryBlockDevice
import dev.bootforge.blockdev.slice
import dev.bootforge.fs.exfat.ExFatVolume
import dev.bootforge.fs.fat.FatVolume
import dev.bootforge.integrity.HashAlgorithm
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ferramentas de pendrive (Etapa 8), conferidas com ferramentas independentes do Linux
 * (fsck.fat, fsck.exfat, sfdisk, sgdisk, md5sum, sha*sum, gzip). Testes sem a ferramenta são pulados.
 */
class MediaToolsTest {

    private fun tool(n: String) = listOf("/usr/bin/$n", "/usr/sbin/$n", "/sbin/$n").firstOrNull { File(it).exists() }

    private fun run(vararg cmd: String, ok: Boolean = true, stdin: File? = null): String {
        val pb = ProcessBuilder(*cmd).redirectErrorStream(true)
        if (stdin != null) pb.redirectInput(stdin)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(300, TimeUnit.SECONDS)
        if (ok) assertEquals(0, p.exitValue(), "${cmd.joinToString(" ")}\n$out")
        return out
    }

    private fun work() = File.createTempFile("bf-mt", "").apply { delete(); mkdirs() }

    /** Copia a partição (a partir de 1 MiB) para um arquivo próprio, para o fsck. */
    private fun extractPartition(img: File, start: Long, sectors: Long, out: File) {
        java.io.RandomAccessFile(img, "r").use { src ->
            java.io.RandomAccessFile(out, "rw").use { dst ->
                dst.setLength(sectors * 512)
                val buf = ByteArray(1 shl 20)
                var pos = 0L
                val total = sectors * 512
                while (pos < total) {
                    val n = minOf(buf.size.toLong(), total - pos).toInt()
                    src.seek(start * 512 + pos); src.readFully(buf, 0, n)
                    if (buf.any { it != 0.toByte() }) { dst.seek(pos); dst.write(buf, 0, n) }
                    pos += n
                }
            }
        }
    }

    // ------------------------------------------------------------------ formatar

    @Test
    fun formatsFat32AndExfatWithMbrAndGpt() {
        val dir = work()
        try {
            for (fs in MediaFs.values()) for (gpt in listOf(false, true)) {
                val img = File(dir, "usb-$fs-$gpt.img")
                FileBlockDevice.create(img, 300L shl 20).use { dev ->
                    // "sujeira" de uma gravação anterior no início e no fim
                    dev.write(0, ByteArray(512) { 0x77 })
                    dev.write(dev.blockCount - 1, ByteArray(512) { 0x77 })
                    val r = MediaFormatter.format(dev, FormatRequest(fs, gpt, "Meu Pendrive"))
                    assertEquals(2048L, r.partitionStart)
                    val part = dev.slice(r.partitionStart, r.partitionSectors)
                    when (fs) {
                        MediaFs.FAT32 -> { assertEquals("MEU PENDRIV", r.label); FatVolume.open(part).list("/") }
                        MediaFs.EXFAT -> { assertEquals("Meu Pendriv", r.label); assertTrue(ExFatVolume.open(part).list("/").isEmpty()) }
                    }
                    val sfdisk = tool("sfdisk")
                    if (sfdisk != null) {
                        val out = run(sfdisk, "--dump", img.path)
                        assertTrue(out.contains(if (gpt) "label: gpt" else "label: dos"), out)
                        assertTrue(out.contains("start=        2048"), out)
                        if (!gpt) assertTrue(out.contains(if (fs == MediaFs.FAT32) "type=c" else "type=7"), out)
                        else assertTrue(out.contains("EBD0A0A2-B9E5-4433-87C0-68B6B72699C7"), out)
                    }
                    if (gpt) tool("sgdisk")?.let { assertTrue(run(it, "-v", img.path).contains("No problems found"), "sgdisk $fs") }
                    val p = File(dir, "part-$fs-$gpt.img")
                    extractPartition(img, r.partitionStart, r.partitionSectors, p)
                    when (fs) {
                        MediaFs.FAT32 -> tool("fsck.fat")?.let { run(it, "-n", "-v", p.path) }
                        MediaFs.EXFAT -> tool("fsck.exfat")?.let { run(it, "-n", p.path) }
                    }
                    p.delete()
                }
                img.delete()
            }
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun labelsAreSanitizedAndClusterSizesFollowMicrosoftDefaults() {
        assertEquals("FOTOS_2026", MediaFormatter.sanitizeLabel("fotos.2026", MediaFs.FAT32))
        assertEquals("PENDRIVE", MediaFormatter.sanitizeLabel("   ", MediaFs.FAT32))
        assertEquals("Fotos Ensai", MediaFormatter.sanitizeLabel("Fotos Ensaio", MediaFs.EXFAT))
        assertEquals("Ação", MediaFormatter.sanitizeLabel("Ação", MediaFs.EXFAT))
        assertEquals(4096, MediaFormatter.exfatCluster(200L shl 20))
        assertEquals(32768, MediaFormatter.exfatCluster(16L shl 30))
        assertEquals(131072, MediaFormatter.exfatCluster(64L shl 30))
    }

    @Test
    fun formatFailsCleanlyWhenTheDriveIsUnplugged() {
        val dir = work()
        try {
            val inner = FileBlockDevice.create(File(dir, "u.img"), 128L shl 20)
            val dev = FaultInjectingBlockDevice(inner, failAfterWrittenBytes = 3L shl 20)
            assertFailsWith<IOException> { MediaFormatter.format(dev, FormatRequest(MediaFs.FAT32)) }
            inner.close()
        } finally { dir.deleteRecursively() }
    }

    // ------------------------------------------------------------------ superfície

    @Test
    fun surfaceTestOnHealthyDriveReportsNoProblems() {
        val dev = MemoryBlockDevice(40 shl 20)
        val r = SurfaceTest.run(dev)
        assertTrue(r.healthy)
        assertEquals(0L, r.badBytes)
        assertEquals(dev.sizeBytes, r.realBytes)
        assertEquals(40, r.regions.size)
        assertTrue(r.writeBytesPerSec > 0 && r.readBytesPerSec > 0)
    }

    @Test
    fun surfaceTestFindsBadSectorsAndReadErrors() {
        val mem = MemoryBlockDevice(32 shl 20)
        val dev = object : BlockDevice by mem {
            override fun read(lba: Long, buffer: ByteArray, offset: Int, length: Int) {
                mem.read(lba, buffer, offset, length)
                val byte = lba * 512
                // 1 setor "ruim" em 5 MB (um bit trocado) e erro de leitura nos MB 20 e 21
                if (byte <= 5L * 1048576 + 4096 && 5L * 1048576 + 4096 < byte + length) buffer[offset + (5 * 1048576 + 4096 - byte).toInt()] = buffer[offset + (5 * 1048576 + 4096 - byte).toInt()].inc()
                if (byte >= 20L shl 20 && byte < 22L shl 20) throw IOException("erro de leitura simulado")
            }
        }
        val r = SurfaceTest.run(dev)
        assertFalse(r.healthy)
        assertFalse(r.fake)
        assertEquals(3L shl 20, r.badBytes)
        assertEquals(listOf(SurfaceTest.BadRange(5L shl 20, 1L shl 20), SurfaceTest.BadRange(20L shl 20, 2L shl 20)), r.badRanges)
        assertEquals(dev.sizeBytes, r.realBytes)
    }

    @Test
    fun surfaceTestDetectsFakeDriveThatWrapsAround() {
        // declara 64 MB mas só tem 16 MB: o endereço "dá a volta"
        val real = MemoryBlockDevice(16 shl 20)
        val fake = object : BlockDevice {
            override val blockSize = 512
            override val blockCount = (64L shl 20) / 512
            override val isReadOnly = false
            override fun read(lba: Long, buffer: ByteArray, offset: Int, length: Int) = real.read(lba % real.blockCount, buffer, offset, length)
            override fun write(lba: Long, buffer: ByteArray, offset: Int, length: Int) = real.write(lba % real.blockCount, buffer, offset, length)
            override fun flush() = Unit
            override fun close() = Unit
        }
        val r = SurfaceTest.run(fake)
        assertTrue(r.fake); assertTrue(r.wraps)
        assertEquals(16L shl 20, r.realBytes)
    }

    @Test
    fun surfaceTestDetectsFakeDriveThatDiscardsData() {
        // declara 64 MB; depois de 24 MB descarta o que é gravado e devolve zeros
        val mem = MemoryBlockDevice(64 shl 20)
        val limit = (24L shl 20) / 512
        val fake = object : BlockDevice by mem {
            override fun write(lba: Long, buffer: ByteArray, offset: Int, length: Int) { if (lba < limit) mem.write(lba, buffer, offset, length) }
        }
        val r = SurfaceTest.run(fake)
        assertTrue(r.fake); assertFalse(r.wraps)
        assertEquals(24L shl 20, r.realBytes)
        assertEquals(40L shl 20, r.badBytes)
    }

    @Test
    fun surfaceTestStopsWhenTheDriveStopsResponding() {
        val dev = FaultInjectingBlockDevice(MemoryBlockDevice(48 shl 20), failAfterWrittenBytes = 10L shl 20)
        val e = assertFailsWith<IOException> { SurfaceTest.run(dev) }
        assertTrue(e.message!!.contains("parou de responder"), e.message)
    }

    // ------------------------------------------------------------------ apagar

    @Test
    fun quickAndFullEraseZeroWhatTheyPromise() {
        val size = 96 shl 20
        val quick = MemoryBlockDevice(Random(1).nextBytes(size))
        val q = SecureErase.run(quick, EraseMode.QUICK)
        assertEquals((64L + 16) shl 20, q.bytesZeroed)
        assertTrue(quick.data.copyOfRange(0, 64 shl 20).all { it == 0.toByte() })
        assertTrue(quick.data.copyOfRange(size - (16 shl 20), size).all { it == 0.toByte() })
        assertTrue(quick.data.copyOfRange(64 shl 20, 80 shl 20).any { it != 0.toByte() }, "o meio não é apagado no modo rápido")

        val full = MemoryBlockDevice(Random(2).nextBytes(size))
        val f = SecureErase.run(full, EraseMode.FULL)
        assertEquals(size.toLong(), f.bytesZeroed)
        assertTrue(full.data.all { it == 0.toByte() })
        assertTrue(f.samplesChecked > 200)

        // pendrive que ignora gravações: a conferência tem de pegar
        val liar = MemoryBlockDevice(Random(3).nextBytes(8 shl 20))
        val ignoring = object : BlockDevice by liar { override fun write(lba: Long, buffer: ByteArray, offset: Int, length: Int) = Unit }
        assertFailsWith<IOException> { SecureErase.run(ignoring, EraseMode.FULL) }
    }

    // ------------------------------------------------------------------ backup / restauração

    @Test
    fun backupToGzipAndRestoreRoundTrip() {
        val dir = work()
        try {
            val size = 24 shl 20
            val data = ByteArray(size).also { val r = Random(7); for (i in 0 until size step 4096) if (r.nextInt(4) == 0) r.nextBytes(it, i, i + 4096) }
            val src = MemoryBlockDevice(data.copyOf())
            val gz = File(dir, "backup.img.gz")
            val b = gz.outputStream().use { ImageBackup.backup(src, it) }
            assertEquals(size.toLong(), b.bytes)
            assertTrue(gz.length() < size / 2, "compactou: ${gz.length()}")
            tool("gzip")?.let { gzip ->
                val raw = File(dir, "backup.img")
                ProcessBuilder(gzip, "-dc", gz.path).redirectOutput(raw).start().waitFor(60, TimeUnit.SECONDS)
                assertEquals(b.sha256, run(tool("sha256sum")!!, raw.path).substringBefore(' '))
            }
            // medir e restaurar num pendrive maior
            val measured = gz.inputStream().use { ImageBackup.measure(it) }
            assertEquals(size.toLong(), measured)
            val dst = MemoryBlockDevice(32 shl 20)
            val r = gz.inputStream().use { ImageBackup.restore(dst, it, measured) }
            assertTrue(r.verified); assertEquals(b.sha256, r.sha256)
            assertTrue(dst.data.copyOf(size).contentEquals(data))
            // imagem sem compactação também é aceita
            val raw2 = ByteArrayOutputStream().also { ImageBackup.backup(src, it, compress = false) }.toByteArray()
            assertEquals(size, raw2.size)
            assertEquals(size.toLong(), ImageBackup.measure(ByteArrayInputStream(raw2)))
            // pendrive menor: recusa ANTES de gravar
            val small = MemoryBlockDevice(16 shl 20)
            val e = assertFailsWith<IOException> { gz.inputStream().use { ImageBackup.restore(small, it, measured) } }
            assertTrue(e.message!!.contains("maior que este pendrive"))
            assertTrue(small.data.all { it == 0.toByte() })
        } finally { dir.deleteRecursively() }
    }

    // ------------------------------------------------------------------ checksum

    @Test
    fun checksumsMatchCoreutils() {
        val dir = work()
        try {
            val f = File(dir, "arquivo.bin").apply { writeBytes(Random(9).nextBytes(3_500_001)) }
            val res = f.inputStream().use { Checksums.compute(it, HashAlgorithm.values().toList(), f.length()) }
            for ((alg, cmd) in mapOf(HashAlgorithm.MD5 to "md5sum", HashAlgorithm.SHA1 to "sha1sum", HashAlgorithm.SHA256 to "sha256sum", HashAlgorithm.SHA512 to "sha512sum")) {
                val t = tool(cmd) ?: continue
                assertEquals(run(t, f.path).substringBefore(' '), res[alg], cmd)
            }
            assertEquals(HashAlgorithm.SHA256, Checksums.match("  SHA256: ${res[HashAlgorithm.SHA256]!!.uppercase()} ", res))
            assertEquals(HashAlgorithm.MD5, Checksums.guess(res[HashAlgorithm.MD5]!!))
            assertEquals(HashAlgorithm.SHA512, Checksums.guess(res[HashAlgorithm.SHA512]!!))
            assertNull(Checksums.match("0".repeat(64), res))
        } finally { dir.deleteRecursively() }
    }
}
