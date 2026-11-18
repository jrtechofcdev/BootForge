package dev.bootforge.isoedit

import dev.bootforge.blockdev.ProgressListener
import dev.bootforge.blockdev.ProgressTracker
import dev.bootforge.blockdev.RandomReader
import dev.bootforge.integrity.CancellationSignal
import dev.bootforge.integrity.HashAlgorithm
import dev.bootforge.integrity.toHex
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Um pedaço da imagem final, numa posição fixa (bytes). A imagem é descrita como uma lista de
 * pedaços que não se sobrepõem; o que fica entre eles é preenchido com zeros. Assim a gravação
 * é SEQUENCIAL (do início ao fim), o que permite gravar direto num arquivo do celular ou do
 * pendrive, sem precisar de acesso aleatório à saída.
 */
internal sealed class Piece(val offset: Long, val length: Long) {
    val end get() = offset + length
}

/** Bytes gerados (descritores, pastas, tabelas). */
internal class BytesPiece(offset: Long, val data: ByteArray) : Piece(offset, data.size.toLong())

/** Cópia de um trecho da imagem original, com pequenas correções ([patches]: posição relativa → bytes). */
internal class CopyPiece(offset: Long, val srcOffset: Long, length: Long, val patches: List<Pair<Long, ByteArray>> = emptyList()) : Piece(offset, length)

/** Dados de um arquivo (lidos sob demanda). O tamanho tem de bater exatamente. */
internal class StreamPiece(offset: Long, length: Long, val label: String, val open: () -> InputStream) : Piece(offset, length)

internal class ImageLayout(val pieces: List<Piece>, val totalBytes: Long) {

    init {
        val sorted = pieces.sortedBy { it.offset }
        for (i in 1 until sorted.size) {
            if (sorted[i].offset < sorted[i - 1].end) throw IllegalStateException("Sobreposição na montagem da imagem em ${sorted[i].offset} (${sorted[i - 1]}, ${sorted[i]})")
        }
        if (sorted.isNotEmpty() && sorted.last().end > totalBytes) throw IllegalStateException("Pedaço além do fim da imagem")
    }

    /** Grava a imagem inteira em [out] e devolve o SHA-256 do que foi gravado. */
    fun write(src: RandomReader, out: OutputStream, listener: ProgressListener, cancel: CancellationSignal, stage: String = "Gerando ISO"): String {
        val md = HashAlgorithm.SHA256.newDigest()
        val tracker = ProgressTracker(stage, totalBytes, listener)
        val buf = ByteArray(1 shl 20)
        var pos = 0L
        fun emit(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len); md.update(b, off, len); pos += len; tracker.update(pos)
        }
        fun zeros(n: Long) {
            var left = n
            java.util.Arrays.fill(buf, 0)
            while (left > 0) { cancel.throwIfCancelled(); val k = minOf(left, buf.size.toLong()).toInt(); emit(buf, 0, k); left -= k }
        }
        for (p in pieces.sortedBy { it.offset }) {
            zeros(p.offset - pos)
            when (p) {
                is BytesPiece -> emit(p.data, 0, p.data.size)
                is CopyPiece -> {
                    var done = 0L
                    val patches = p.patches.sortedBy { it.first }
                    while (done < p.length) {
                        cancel.throwIfCancelled()
                        val k = minOf(buf.size.toLong(), p.length - done).toInt()
                        var got = 0
                        while (got < k) {
                            val n = src.read(p.srcOffset + done + got, buf, got, k - got)
                            if (n <= 0) throw IOException("Imagem original terminou antes do esperado")
                            got += n
                        }
                        for ((rel, bytes) in patches) { // aplica as correções que caem neste bloco
                            val s = maxOf(rel, done); val e = minOf(rel + bytes.size, done + k)
                            if (s < e) bytes.copyInto(buf, (s - done).toInt(), (s - rel).toInt(), (e - rel).toInt())
                        }
                        emit(buf, 0, k)
                        done += k
                    }
                }
                is StreamPiece -> p.open().use { input ->
                    var done = 0L
                    while (done < p.length) {
                        cancel.throwIfCancelled()
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), p.length - done).toInt())
                        if (n < 0) throw IOException("Arquivo terminou antes do esperado: ${p.label}")
                        emit(buf, 0, n)
                        done += n
                    }
                    if (input.read() >= 0) throw IOException("Arquivo maior que o esperado (mudou durante a gravação?): ${p.label}")
                }
            }
        }
        zeros(totalBytes - pos)
        out.flush()
        tracker.update(pos, force = true)
        return md.digest().toHex()
    }
}

/** Distribui setores (2048 bytes) em sequência a partir de [next]. */
internal class SectorAllocator(var next: Long) {
    fun alloc(sectors: Long): Long { val l = next; next += maxOf(0, sectors); return l }
    fun allocBytes(bytes: Long): Long = alloc((bytes + 2047) / 2048)
}
