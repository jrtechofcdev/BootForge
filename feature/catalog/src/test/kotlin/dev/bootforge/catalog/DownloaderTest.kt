package dev.bootforge.catalog

import com.sun.net.httpserver.HttpServer
import dev.bootforge.blockdev.ProgressListener
import dev.bootforge.integrity.Hashing
import dev.bootforge.integrity.OperationCancelledException
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Downloads contra um servidor HTTP local que imita um espelho real: aceita Range/If-Range,
 * manda ETag e pode "derrubar" a conexão no meio, mudar o arquivo ou ignorar o Range.
 */
class DownloaderTest {
    private val content = Random(42).nextBytes(3_000_000)
    private var etag = "\"v1\""
    private var dropAfter = -1L          // derruba a conexão depois de N bytes (uma vez)
    private var ignoreRange = false
    private var throttle = false
    private val requests = AtomicInteger()
    private lateinit var server: HttpServer
    private lateinit var dir: File
    private val url get() = "http://127.0.0.1:${server.address.port}/iso/teste.iso"

    @BeforeTest
    fun start() {
        dir = File.createTempFile("bf-dl", "").apply { delete(); mkdirs() }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/iso/teste.iso") { ex ->
            requests.incrementAndGet()
            val range = ex.requestHeaders.getFirst("Range")
            val ifRange = ex.requestHeaders.getFirst("If-Range")
            var start = 0L
            val partial = range != null && !ignoreRange && (ifRange == null || ifRange == etag)
            if (partial) start = Regex("bytes=(\\d+)-").find(range!!)!!.groupValues[1].toLong()
            ex.responseHeaders.add("ETag", etag)
            ex.responseHeaders.add("Accept-Ranges", "bytes")
            val len = content.size - start
            if (partial) {
                ex.responseHeaders.add("Content-Range", "bytes $start-${content.size - 1}/${content.size}")
                ex.sendResponseHeaders(206, len)
            } else ex.sendResponseHeaders(200, len)
            try {
                ex.responseBody.use { out ->
                    var pos = start
                    while (pos < content.size) {
                        val n = minOf(32_768L, content.size - pos).toInt()
                        if (dropAfter in 0..pos) { dropAfter = -1; throw IOException("queda simulada") }
                        out.write(content, pos.toInt(), n); pos += n
                        if (throttle) Thread.sleep(5)
                    }
                }
            } catch (_: IOException) { ex.close() }
        }
        server.start()
    }

    @AfterTest
    fun stop() { server.stop(0); dir.deleteRecursively() }

    private fun req(checksum: String? = Hashing.hash(content), size: Long? = content.size.toLong()) =
        DownloadRequest(url, "teste.iso", "Sistema de teste", "1.0", "teste", size, checksum?.let { Checksum("SHA-256", it, "teste") }, Verification.SIGNED, "ABCD")

    @Test
    fun downloadsVerifiesAndRegistersInLibrary() {
        val lib = Library(dir)
        val item = Downloader(dir).download(req(), lib, ProgressListener.NONE, DownloadControl())
        assertEquals(ItemStatus.SIGNED_OK, item.status)
        assertContentEquals(content, File(dir, "teste.iso").readBytes())
        assertEquals(1, Library(dir).items().size) // persistido
        assertEquals(item.fileName, Library(dir).find("teste", "1.0", null)!!.fileName) // reutilização
    }

    @Test
    fun connectionDropIsResumedAutomatically() {
        dropAfter = 1_000_000
        val item = Downloader(dir).download(req(), Library(dir), ProgressListener.NONE, DownloadControl())
        assertTrue(item.status.verified)
        assertTrue(requests.get() >= 2, "deveria ter feito uma segunda requisição (Range)")
        assertContentEquals(content, File(dir, "teste.iso").readBytes())
    }

    @Test
    fun pauseKeepsPartialAndResumesLaterEvenWithNewInstance() {
        throttle = true
        val control = DownloadControl()
        Thread { Thread.sleep(400); control.pause() }.start()
        val e = assertFailsWith<DownloadPausedException> { Downloader(dir).download(req(), Library(dir), ProgressListener.NONE, control) }
        assertTrue(e.bytes in 1 until content.size, "pausou com ${e.bytes} bytes")
        assertEquals(e.bytes, Downloader(dir).partialBytes(req()))
        throttle = false
        val item = Downloader(dir).download(req(), Library(dir), ProgressListener.NONE, DownloadControl()) // "reabriu o app"
        assertTrue(item.status.verified)
        assertContentEquals(content, File(dir, "teste.iso").readBytes())
    }

    @Test
    fun cancelDeletesPartial() {
        throttle = true
        val control = DownloadControl()
        Thread { Thread.sleep(300); control.cancel() }.start()
        assertFailsWith<OperationCancelledException> { Downloader(dir).download(req(), Library(dir), ProgressListener.NONE, control) }
        assertFalse(File(dir, "teste.iso.part").exists())
        assertFalse(File(dir, "teste.iso").exists())
    }

    @Test
    fun changedFileOnServerRestartsFromZero() {
        dropAfter = 500_000
        val d = Downloader(dir, autoRetries = 0)
        assertFailsWith<IOException> { d.download(req(), Library(dir), ProgressListener.NONE, DownloadControl()) }
        etag = "\"v2\"" // o arquivo "mudou": If-Range não confere → servidor manda tudo (200)
        val item = Downloader(dir).download(req(), Library(dir), ProgressListener.NONE, DownloadControl())
        assertTrue(item.status.verified)
        assertContentEquals(content, File(dir, "teste.iso").readBytes())
    }

    @Test
    fun serverIgnoringRangeStillProducesCorrectFile() {
        dropAfter = 700_000
        ignoreRange = true
        val item = Downloader(dir).download(req(), Library(dir), ProgressListener.NONE, DownloadControl())
        assertTrue(item.status.verified)
        assertContentEquals(content, File(dir, "teste.iso").readBytes())
    }

    @Test
    fun wrongChecksumDeletesFileAndFails() {
        val e = assertFailsWith<IOException> { Downloader(dir).download(req(checksum = "0".repeat(64)), Library(dir), ProgressListener.NONE, DownloadControl()) }
        assertTrue(e.message!!.contains("NÃO CONFERE"))
        assertFalse(File(dir, "teste.iso").exists())
        assertTrue(Library(dir).items().isEmpty())
    }

    @Test
    fun wrongSizeIsRefusedAndNoChecksumIsMarkedUnverified() {
        assertFailsWith<IOException> { Downloader(dir).download(req(size = 123), Library(dir), ProgressListener.NONE, DownloadControl()) }
        val item = Downloader(dir).download(req(checksum = null).copy(verification = Verification.NONE), Library(dir), ProgressListener.NONE, DownloadControl())
        assertEquals(ItemStatus.UNVERIFIED, item.status)
        assertFalse(item.status.verified)
        assertEquals(Hashing.hash(content), item.sha256) // o hash calculado fica registrado
    }

    @Test
    fun insufficientSpaceIsReportedBeforeDownloading() {
        val e = assertFailsWith<InsufficientSpaceException> {
            Downloader(dir, freeSpace = { 1_000_000L }).download(req(), Library(dir), ProgressListener.NONE, DownloadControl())
        }
        assertTrue(e.message!!.contains("Espaço insuficiente"), e.message)
        assertEquals(1, requests.get(), "não deve tentar de novo sozinho quando falta espaço")
        assertEquals(0L, Downloader(dir).partialBytes(req()))
        // depois de liberar espaço, o download funciona
        assertTrue(Downloader(dir).download(req(), Library(dir), ProgressListener.NONE, DownloadControl()).status.verified)
    }

    /** Download REAL (internet) do Alpine virt com pausa e retomada. Só com BOOTFORGE_LIVE=1. */
    @Test
    fun liveAlpineDownloadWithPauseAndResume() {
        if (System.getenv("BOOTFORGE_LIVE") != "1") return
        val def = CatalogDefinition.bundled()
        val s = def.systems.first { it.id == "alpine-standard" }
        val virt = s.copy(id = "alpine-virt", resolver = s.resolver + ("flavor" to "alpine-virt"))
        val r = Resolvers(UrlConnectionHttp()) { error("sem chaves") }.resolve(virt)
        val control = DownloadControl()
        var paused = false
        val lib = Library(dir)
        val e = runCatching {
            Downloader(dir).download(DownloadRequest.of(r), lib, { p -> if (!paused && p.bytesDone > 15_000_000) { paused = true; control.pause() } }, control)
        }.exceptionOrNull()
        assertTrue(e is DownloadPausedException, "deveria pausar: $e")
        val item = Downloader(dir).download(DownloadRequest.of(r), lib, ProgressListener.NONE, DownloadControl())
        println("Alpine ${r.version}: ${item.sizeBytes} bytes, ${item.status.label}, ${r.checksum!!.algorithm} conferido")
        assertEquals(ItemStatus.CHECKSUM_OK, item.status)
    }
}
