package dev.bootforge.mediatools

import dev.bootforge.blockdev.BlockDevice
import dev.bootforge.blockdev.BlockTransfer
import dev.bootforge.blockdev.Progress
import dev.bootforge.blockdev.ProgressListener
import dev.bootforge.blockdev.ProgressTracker
import dev.bootforge.integrity.CancellationSignal
import dev.bootforge.integrity.Diag
import dev.bootforge.integrity.HashAlgorithm
import dev.bootforge.integrity.toHex
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

// ====================================================================== Apagar com segurança

enum class EraseMode(val title: String, val summary: String) {
    QUICK(
        "Rápido",
        "Apaga as tabelas de partição e as estruturas dos sistemas de arquivos (primeiros 64 MB e o fim). " +
            "O pendrive fica \"vazio\", mas programas de recuperação ainda achariam arquivos. Leva segundos.",
    ),
    FULL(
        "Completo",
        "Grava zeros no pendrive INTEIRO e confere. Os dados deixam de ser recuperáveis por programas comuns. " +
            "Leva o mesmo tempo de encher o pendrive.",
    ),
}

data class EraseResult(val mode: EraseMode, val bytesZeroed: Long, val samplesChecked: Int)

/**
 * Apagamento de pendrive/cartão. Aviso honesto (também mostrado na tela): memórias flash têm
 * áreas reservadas internas (wear leveling) que o celular não alcança; para dados muito
 * sensíveis, o único método garantido é destruir fisicamente a memória.
 */
object SecureErase {
    private const val CHUNK = 1 shl 20

    fun run(dev: BlockDevice, mode: EraseMode, listener: ProgressListener = ProgressListener.NONE, cancel: CancellationSignal = CancellationSignal.NONE): EraseResult {
        if (dev.isReadOnly) throw IOException("Dispositivo aberto somente para leitura")
        val bs = dev.blockSize
        val total = dev.sizeBytes
        val ranges: List<Pair<Long, Long>> = when (mode) {
            EraseMode.FULL -> listOf(0L to total)
            EraseMode.QUICK -> {
                val head = minOf(total, 64L shl 20)
                val tailStart = maxOf(head, total - (16L shl 20))
                listOf(0L to head, tailStart to total - tailStart).filter { it.second > 0 }
            }
        }
        val toZero = ranges.sumOf { it.second }
        val tracker = ProgressTracker("Apagando", toZero, listener)
        val zero = ByteArray(CHUNK)
        var done = 0L
        Diag.i("Apagar", "${mode.title}: $toZero bytes")
        for ((start, len) in ranges) {
            var off = 0L
            while (off < len) {
                cancel.throwIfCancelled()
                val n = (minOf(CHUNK.toLong(), len - off) / bs * bs).toInt()
                if (n == 0) break
                dev.write((start + off) / bs, zero, 0, n)
                off += n; done += n
                tracker.update(done)
            }
        }
        dev.flush()
        // conferência: início, fim e amostras aleatórias precisam estar zerados
        val rnd = SecureRandom()
        val buf = ByteArray(64 * 1024)
        val points = buildList {
            ranges.forEach { (s, l) -> add(s); add(s + l - buf.size) }
            repeat(if (mode == EraseMode.FULL) 256 else 32) {
                val (s, l) = ranges[rnd.nextInt(ranges.size)]
                if (l > buf.size) add(s + (rnd.nextLong() and Long.MAX_VALUE) % (l - buf.size))
            }
        }.filter { it >= 0 }.map { it / bs * bs }
        for (p in points) {
            cancel.throwIfCancelled()
            val n = (minOf(buf.size.toLong(), total - p) / bs * bs).toInt()
            dev.read(p / bs, buf, 0, n)
            if ((0 until n).any { buf[it] != 0.toByte() }) throw IOException("Verificação falhou: ainda há dados perto de ${p / 1048576} MB (o pendrive não gravou os zeros)")
        }
        tracker.update(done, force = true)
        return EraseResult(mode, done, points.size)
    }
}

// ====================================================================== Backup e restauração

data class BackupResult(val bytes: Long, val sha256: String)
data class RestoreResult(val bytes: Long, val sha256: String, val verified: Boolean)

/**
 * Cópia completa de pendrive/cartão para uma imagem (`.img.gz`, compatível com Rufus,
 * balenaEtcher, `gunzip | dd` no PC) e restauração com conferência por releitura.
 */
object ImageBackup {
    private const val CHUNK = 1 shl 20

    /** Lê o dispositivo inteiro e grava em [out] (compactado com gzip se [compress]). Devolve o SHA-256 dos dados crus. */
    fun backup(dev: BlockDevice, out: OutputStream, compress: Boolean = true, listener: ProgressListener = ProgressListener.NONE, cancel: CancellationSignal = CancellationSignal.NONE): BackupResult {
        val total = dev.sizeBytes
        val md = HashAlgorithm.SHA256.newDigest()
        val tracker = ProgressTracker("Copiando o pendrive", total, listener)
        val sink: OutputStream = if (compress) object : GZIPOutputStream(out, CHUNK) { init { def.setLevel(Deflater.BEST_SPEED) } } else out
        val buf = ByteArray(CHUNK)
        var pos = 0L
        Diag.i("Backup", "Início: $total bytes, compactado=$compress")
        while (pos < total) {
            cancel.throwIfCancelled()
            val n = minOf(CHUNK.toLong(), total - pos).toInt()
            dev.read(pos / dev.blockSize, buf, 0, n)
            md.update(buf, 0, n)
            sink.write(buf, 0, n)
            pos += n
            tracker.update(pos)
        }
        if (sink is GZIPOutputStream) sink.finish()
        sink.flush()
        tracker.update(pos, force = true)
        return BackupResult(total, md.digest().toHex())
    }

    /** true se os primeiros bytes indicam gzip. */
    fun isGzip(head: ByteArray) = head.size >= 2 && (head[0].toInt() and 0xFF) == 0x1F && (head[1].toInt() and 0xFF) == 0x8B

    /** Abre uma imagem (descompactando se for .gz). */
    fun open(input: InputStream): InputStream {
        val b = input.buffered(CHUNK)
        b.mark(4)
        val head = ByteArray(2).also { b.read(it) }
        b.reset()
        return if (isGzip(head)) GZIPInputStream(b, CHUNK) else b
    }

    /** Mede o tamanho real (descompactado) da imagem ANTES de gravar — confere se cabe no pendrive. */
    fun measure(input: InputStream, listener: ProgressListener = ProgressListener.NONE, cancel: CancellationSignal = CancellationSignal.NONE): Long {
        val buf = ByteArray(CHUNK)
        var total = 0L
        var last = 0L
        val start = System.nanoTime()
        open(input).use { s ->
            while (true) {
                cancel.throwIfCancelled()
                val n = s.read(buf); if (n < 0) break
                total += n
                if (System.nanoTime() - last > 300_000_000L) { last = System.nanoTime(); listener.onProgress(Progress("Conferindo a imagem", total, 0, total / ((System.nanoTime() - start) / 1e9).coerceAtLeast(0.001))) }
            }
        }
        return total
    }

    /** Grava a imagem (de tamanho já medido) a partir do setor 0 e confere relendo. **Apaga o pendrive.** */
    fun restore(dev: BlockDevice, input: InputStream, imageBytes: Long, listener: ProgressListener = ProgressListener.NONE, cancel: CancellationSignal = CancellationSignal.NONE): RestoreResult {
        if (imageBytes > dev.sizeBytes) {
            throw IOException("A imagem (${imageBytes / 1048576} MB) é maior que este pendrive (${dev.sizeBytes / 1048576} MB). Use um pendrive igual ou maior.")
        }
        if (imageBytes <= 0) throw IOException("Imagem vazia ou inválida")
        Diag.i("Restaurar", "Gravando imagem de $imageBytes bytes")
        val res = open(input).use { BlockTransfer.writeStream(dev, 0, it, imageBytes, "Restaurando", listener, cancel) }
        val back = BlockTransfer.hashRegion(dev, 0, imageBytes, "Verificando", listener, cancel)
        if (back != res.sha256) throw IOException("Verificação falhou: o conteúdo relido do pendrive é diferente da imagem")
        return RestoreResult(imageBytes, res.sha256, true)
    }
}

// ====================================================================== Checksum

/** Calcula vários checksums de uma vez (uma única leitura do arquivo). */
object Checksums {
    fun compute(
        input: InputStream, algorithms: Collection<HashAlgorithm>, totalBytes: Long?,
        listener: ProgressListener = ProgressListener.NONE, cancel: CancellationSignal = CancellationSignal.NONE,
    ): Map<HashAlgorithm, String> {
        val digests = algorithms.associateWith { it.newDigest() }
        val buf = ByteArray(1 shl 20)
        val tracker = ProgressTracker("Calculando", totalBytes ?: 0, listener)
        var done = 0L
        while (true) {
            cancel.throwIfCancelled()
            val n = input.read(buf); if (n < 0) break
            digests.values.forEach { it.update(buf, 0, n) }
            done += n
            tracker.update(done)
        }
        tracker.update(done, force = true)
        return digests.mapValues { it.value.digest().toHex() }
    }

    /** Qual algoritmo bate com o valor colado (ignora maiúsculas, espaços e prefixos como "sha256:"). */
    fun match(expected: String, results: Map<HashAlgorithm, String>): HashAlgorithm? {
        val clean = normalize(expected)
        return results.entries.firstOrNull { it.value.equals(clean, ignoreCase = true) }?.key
    }

    fun normalize(s: String) = s.trim().substringAfterLast(':').filter { !it.isWhitespace() }.lowercase()

    /** Algoritmo provável de um valor colado, pelo tamanho. */
    fun guess(expected: String): HashAlgorithm? = HashAlgorithm.fromHexLength(normalize(expected).length)
}
