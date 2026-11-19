package dev.bootforge.mediatools

import dev.bootforge.blockdev.BlockDevice
import dev.bootforge.blockdev.Progress
import dev.bootforge.blockdev.ProgressListener
import dev.bootforge.integrity.CancellationSignal
import dev.bootforge.integrity.Diag
import dev.bootforge.integrity.OperationCancelledException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * TESTE COMPLETO DE SUPERFÍCIE (destrutivo): grava uma marca única em CADA bloco de 1 MB do
 * pendrive inteiro e depois relê tudo, conferindo byte a byte.
 *
 * Detecta: setores ruins (erro de leitura/gravação ou conteúdo diferente), pendrive falsificado
 * (capacidade real exata — "dá a volta" ou "joga fora" os dados), e mede a velocidade real de
 * gravação e leitura em cada região. Diferente do teste rápido de capacidade, este cobre 100% da
 * memória — e APAGA tudo (o app oferece formatar no final).
 */
object SurfaceTest {

    const val BLOCK = 1 shl 20

    data class Region(val index: Int, val startByte: Long, val writeBytesPerSec: Double, val readBytesPerSec: Double)
    data class BadRange(val startByte: Long, val bytes: Long)

    data class Result(
        val declaredBytes: Long,
        val badBytes: Long,
        /** Trechos ruins (já agrupados), no máximo 200. */
        val badRanges: List<BadRange>,
        /** Capacidade que realmente guarda dados. */
        val realBytes: Long,
        /** Falsificado: a partir de certo ponto nada funciona, ou o endereço "dá a volta". */
        val fake: Boolean,
        val wraps: Boolean,
        val regions: List<Region>,
        val writeBytesPerSec: Double,
        val readBytesPerSec: Double,
    ) {
        val healthy: Boolean get() = badBytes == 0L && !fake
    }

    fun run(dev: BlockDevice, listener: ProgressListener = ProgressListener.NONE, cancel: CancellationSignal = CancellationSignal.NONE): Result {
        if (dev.isReadOnly) throw IOException("Dispositivo aberto somente para leitura")
        val bs = dev.blockSize
        val total = dev.sizeBytes
        val blocks = ((total + BLOCK - 1) / BLOCK).toInt()
        val regionCount = minOf(100, blocks)
        val perRegion = (blocks + regionCount - 1) / regionCount
        val writeNs = LongArray(regionCount); val readNs = LongArray(regionCount); val regionBytes = LongArray(regionCount)
        val session = SecureRandom().nextLong()
        val bad = BooleanArray(blocks)
        val wrapDistances = mutableListOf<Long>()
        val buf = ByteArray(BLOCK)
        val expected = ByteArray(BLOCK)
        val started = System.nanoTime()
        Diag.i("Superfície", "Início: $total bytes, $blocks blocos de 1 MB")

        fun len(i: Int): Int = (minOf(BLOCK.toLong(), total - i.toLong() * BLOCK) / bs * bs).toInt()
        fun progress(stage: String, done: Long, phaseStart: Long) {
            val secs = (System.nanoTime() - phaseStart) / 1e9
            listener.onProgress(Progress(stage, done, total, if (secs > 0) done / secs else 0.0))
        }

        // 1) gravação
        var consecutive = 0
        var phase = System.nanoTime()
        var done = 0L
        var lastEmit = 0L
        for (i in 0 until blocks) {
            cancel.throwIfCancelled()
            val n = len(i); if (n == 0) continue
            fill(buf, session, i, n)
            val t0 = System.nanoTime()
            try {
                dev.write(i.toLong() * BLOCK / bs, buf, 0, n)
                consecutive = 0
            } catch (e: OperationCancelledException) { throw e } catch (e: Exception) {
                bad[i] = true
                if (++consecutive >= 16) throw IOException("O pendrive parou de responder durante a gravação (perto de ${i.toLong() * BLOCK / 1048576} MB). Reconecte e tente de novo.", e)
            }
            val r = i / perRegion
            writeNs[r] += System.nanoTime() - t0; regionBytes[r] += n
            done += n
            if (System.nanoTime() - lastEmit > 300_000_000L) { lastEmit = System.nanoTime(); progress("Gravando (1/2)", done, phase) }
        }
        runCatching { dev.flush() }
        val writeTotalNs = System.nanoTime() - phase

        // 2) leitura e conferência
        consecutive = 0
        phase = System.nanoTime(); done = 0L
        for (i in 0 until blocks) {
            cancel.throwIfCancelled()
            val n = len(i); if (n == 0) continue
            val t0 = System.nanoTime()
            val ok = try {
                dev.read(i.toLong() * BLOCK / bs, buf, 0, n)
                consecutive = 0
                true
            } catch (e: OperationCancelledException) { throw e } catch (e: Exception) {
                if (++consecutive >= 16) throw IOException("O pendrive parou de responder durante a leitura (perto de ${i.toLong() * BLOCK / 1048576} MB). Reconecte e tente de novo.", e)
                false
            }
            readNs[i / perRegion] += System.nanoTime() - t0
            if (!ok) bad[i] = true else {
                fill(expected, session, i, n)
                if (!java.util.Arrays.equals(buf, 0, n, expected, 0, n)) {
                    bad[i] = true
                    foreignIndex(buf, session)?.takeIf { it != i }?.let { wrapDistances += kotlin.math.abs(it - i).toLong() * BLOCK }
                }
            }
            done += n
            if (System.nanoTime() - lastEmit > 300_000_000L) { lastEmit = System.nanoTime(); progress("Conferindo (2/2)", done, phase) }
        }
        val readTotalNs = System.nanoTime() - phase

        // 3) análise
        val ranges = mutableListOf<BadRange>()
        var badBytes = 0L
        var i = 0
        while (i < blocks) {
            if (!bad[i]) { i++; continue }
            val s = i
            while (i < blocks && bad[i]) i++
            val bytes = (s until i).sumOf { len(it).toLong() }
            badBytes += bytes
            if (ranges.size < 200) ranges += BadRange(s.toLong() * BLOCK, bytes)
        }
        // "joga fora": tudo ruim de um ponto até o fim (bem antes do fim declarado)
        var tail = blocks
        while (tail > 0 && bad[tail - 1]) tail--
        val tailFake = blocks - tail >= maxOf(2, blocks / 100)
        val wraps = wrapDistances.isNotEmpty()
        val real = when {
            wraps -> minOf(total, wrapDistances.min())
            tailFake -> tail.toLong() * BLOCK
            else -> total
        }
        val regions = (0 until regionCount).map { r ->
            Region(r, r.toLong() * perRegion * BLOCK, rate(regionBytes[r], writeNs[r]), rate(regionBytes[r], readNs[r]))
        }
        Diag.i("Superfície", "Fim em ${(System.nanoTime() - started) / 1_000_000_000}s: ruins=$badBytes bytes em ${ranges.size} trechos, real=$real, falso=${wraps || tailFake}")
        listener.onProgress(Progress("Concluído", total, total, 0.0))
        return Result(total, badBytes, ranges, real, wraps || tailFake, wraps, regions, rate(total, writeTotalNs), rate(total, readTotalNs))
    }

    private fun rate(bytes: Long, ns: Long) = if (ns <= 0) 0.0 else bytes / (ns / 1e9)

    /** Marca do bloco: "BFSURF" + sessão + número do bloco, depois bytes pseudoaleatórios (rápido, 8 bytes por vez). */
    internal fun fill(b: ByteArray, session: Long, index: Int, n: Int) {
        val bb = ByteBuffer.wrap(b, 0, n).order(ByteOrder.LITTLE_ENDIAN)
        bb.putLong(MAGIC); bb.putLong(session); bb.putLong(index.toLong()); bb.putLong(0)
        var x = session xor (index.toLong() * -0x61c8864680b583ebL) or 1L
        while (bb.remaining() >= 8) { x = x xor (x shl 13); x = x xor (x ushr 7); x = x xor (x shl 17); bb.putLong(x) }
        while (bb.hasRemaining()) bb.put(x.toByte())
    }

    private fun foreignIndex(b: ByteArray, session: Long): Int? {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.getLong(0) != MAGIC || bb.getLong(8) != session) return null
        return bb.getLong(16).toInt()
    }

    private const val MAGIC = 0x46525553_46420000L // "\0\0BFSURF" (little-endian)
}
