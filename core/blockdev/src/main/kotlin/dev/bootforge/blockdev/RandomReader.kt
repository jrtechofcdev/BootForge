package dev.bootforge.blockdev

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Leitura em posição arbitrária de um "arquivo" — pode ser um arquivo do celular, um arquivo
 * dentro do pendrive (exFAT) ou um trecho do disco. Usado para analisar ISOs sem copiá-las.
 */
interface RandomReader : Closeable {
    val size: Long

    /** Lê até [len] bytes a partir de [position]; retorna quantos foram lidos (-1 no fim). */
    fun read(position: Long, buffer: ByteArray, offset: Int = 0, len: Int = buffer.size): Int

    /** Lê exatamente [len] bytes ou falha. */
    fun readFully(position: Long, len: Int): ByteArray {
        val b = ByteArray(len)
        var done = 0
        while (done < len) {
            val n = read(position + done, b, done, len - done)
            if (n <= 0) throw IOException("Fim inesperado em ${position + done} (tamanho $size)")
            done += n
        }
        return b
    }

    companion object {
        fun of(file: File): RandomReader = object : RandomReader {
            private val raf = RandomAccessFile(file, "r")
            override val size: Long = raf.length()
            override fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
                if (position >= size) return -1
                raf.seek(position)
                return raf.read(buffer, offset, minOf(len.toLong(), size - position).toInt())
            }
            override fun close() = raf.close()
        }

        fun of(bytes: ByteArray): RandomReader = object : RandomReader {
            override val size: Long = bytes.size.toLong()
            override fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
                if (position >= size) return -1
                val n = minOf(len.toLong(), size - position).toInt()
                bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + n)
                return n
            }
            override fun close() = Unit
        }
    }
}
