package dev.bootforge.blockdev

import dev.bootforge.integrity.CancellationSignal
import dev.bootforge.integrity.HashAlgorithm
import dev.bootforge.integrity.toHex
import java.io.IOException
import java.io.InputStream

/**
 * Progresso REAL de uma operação: sempre calculado a partir dos bytes efetivamente
 * processados (nunca simulado).
 */
data class Progress(
    val stage: String,
    val bytesDone: Long,
    val bytesTotal: Long,
    /** Velocidade média em bytes/segundo desde o início da etapa. */
    val bytesPerSecond: Double,
) {
    val fraction: Float get() = if (bytesTotal <= 0) 0f else (bytesDone.toDouble() / bytesTotal).toFloat().coerceIn(0f, 1f)

    /** Tempo restante estimado em segundos (null quando ainda não há dados suficientes). */
    val etaSeconds: Long?
        get() = if (bytesPerSecond <= 1.0 || bytesTotal <= 0) null
        else ((bytesTotal - bytesDone) / bytesPerSecond).toLong()
}

fun interface ProgressListener {
    fun onProgress(progress: Progress)

    companion object {
        val NONE = ProgressListener { }
    }
}

/** Mede velocidade e repassa o progresso, limitando a frequência das notificações. */
class ProgressTracker(
    private val stage: String,
    private val total: Long,
    private val listener: ProgressListener,
    private val minIntervalMs: Long = 200,
) {
    private val startNs = System.nanoTime()
    private var lastEmitNs = 0L

    fun update(done: Long, force: Boolean = false) {
        val now = System.nanoTime()
        if (!force && now - lastEmitNs < minIntervalMs * 1_000_000) return
        lastEmitNs = now
        val seconds = (now - startNs) / 1e9
        val speed = if (seconds > 0) done / seconds else 0.0
        listener.onProgress(Progress(stage, done, total, speed))
    }
}

/** Resultado de uma gravação: quantos bytes foram gravados e o hash do que foi gravado. */
data class WriteResult(val bytesWritten: Long, val sha256: String)

/** Operações de transferência em massa com progresso, cancelamento e verificação. */
object BlockTransfer {

    /** Tamanho do bloco de transferência. 1 MiB equilibra memória e desempenho no USB. */
    const val CHUNK = 1 shl 20

    /**
     * Grava o conteúdo de [input] no dispositivo a partir de [startLba].
     * O último bloco, se incompleto, é completado com zeros até o tamanho do setor.
     *
     * @param expectedSize tamanho esperado em bytes (para o progresso). Se o stream terminar
     * com tamanho diferente, a operação FALHA — evita gravar uma imagem truncada sem aviso.
     * @return quantidade gravada e SHA-256 dos bytes lidos do stream.
     */
    fun writeStream(
        device: BlockDevice,
        startLba: Long,
        input: InputStream,
        expectedSize: Long,
        stage: String,
        listener: ProgressListener = ProgressListener.NONE,
        cancel: CancellationSignal = CancellationSignal.NONE,
    ): WriteResult {
        val bs = device.blockSize
        val capacityBytes = (device.blockCount - startLba) * bs
        if (expectedSize > capacityBytes) {
            throw IOException("Imagem ($expectedSize bytes) maior que o espaço disponível ($capacityBytes bytes)")
        }
        val digest = HashAlgorithm.SHA256.newDigest()
        val tracker = ProgressTracker(stage, expectedSize, listener)
        val buffer = ByteArray(CHUNK)
        var lba = startLba
        var total = 0L
        while (true) {
            cancel.throwIfCancelled()
            val n = input.readFully(buffer)
            if (n == 0) break
            digest.update(buffer, 0, n)
            var len = n
            if (len % bs != 0) { // completa o último setor com zeros
                val padded = (len + bs - 1) / bs * bs
                buffer.fill(0, len, padded)
                len = padded
            }
            if ((total + len) > capacityBytes) throw IOException("A imagem excede a capacidade do dispositivo")
            device.write(lba, buffer, 0, len)
            lba += len / bs
            total += n
            tracker.update(total)
            if (n < buffer.size) break
        }
        if (total != expectedSize) {
            throw IOException("Tamanho lido ($total) difere do esperado ($expectedSize): imagem incompleta ou corrompida")
        }
        device.flush()
        tracker.update(total, force = true)
        return WriteResult(total, digest.digest().toHex())
    }

    /**
     * Relê [lengthBytes] do dispositivo e calcula o SHA-256 — usado para conferir
     * que o que está no pendrive é exatamente o que foi gravado.
     */
    fun hashRegion(
        device: BlockDevice,
        startLba: Long,
        lengthBytes: Long,
        stage: String,
        listener: ProgressListener = ProgressListener.NONE,
        cancel: CancellationSignal = CancellationSignal.NONE,
    ): String {
        val bs = device.blockSize
        val digest = HashAlgorithm.SHA256.newDigest()
        val tracker = ProgressTracker(stage, lengthBytes, listener)
        val buffer = ByteArray(CHUNK)
        var lba = startLba
        var done = 0L
        while (done < lengthBytes) {
            cancel.throwIfCancelled()
            val want = minOf(buffer.size.toLong(), lengthBytes - done).toInt()
            val aligned = (want + bs - 1) / bs * bs
            device.read(lba, buffer, 0, aligned)
            digest.update(buffer, 0, want)
            lba += aligned / bs
            done += want
            tracker.update(done)
        }
        tracker.update(done, force = true)
        return digest.digest().toHex()
    }

    /** Lê até encher o buffer ou acabar o stream. Retorna quantos bytes foram lidos. */
    private fun InputStream.readFully(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }
}
