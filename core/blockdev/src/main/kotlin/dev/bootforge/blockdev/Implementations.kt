package dev.bootforge.blockdev

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Dispositivo de blocos sobre um arquivo de imagem (.img) no disco.
 * Usado nos testes automatizados e na ferramenta de linha de comando.
 */
class FileBlockDevice private constructor(
    private val raf: RandomAccessFile,
    override val blockSize: Int,
    override val isReadOnly: Boolean,
) : BlockDevice {

    private val channel: FileChannel = raf.channel

    override val blockCount: Long = raf.length() / blockSize

    override fun read(lba: Long, buffer: ByteArray, offset: Int, length: Int) {
        checkRange(lba, buffer, offset, length)
        val bb = ByteBuffer.wrap(buffer, offset, length)
        var pos = lba * blockSize
        while (bb.hasRemaining()) {
            val n = channel.read(bb, pos)
            if (n < 0) throw IOException("Fim inesperado do arquivo em $pos")
            pos += n
        }
    }

    override fun write(lba: Long, buffer: ByteArray, offset: Int, length: Int) {
        if (isReadOnly) throw ReadOnlyDeviceException()
        checkRange(lba, buffer, offset, length)
        val bb = ByteBuffer.wrap(buffer, offset, length)
        var pos = lba * blockSize
        while (bb.hasRemaining()) pos += channel.write(bb, pos)
    }

    override fun flush() {
        if (!isReadOnly) channel.force(true)
    }

    override fun close() = raf.close()

    companion object {
        fun open(file: File, readOnly: Boolean = false, blockSize: Int = 512): FileBlockDevice =
            FileBlockDevice(RandomAccessFile(file, if (readOnly) "r" else "rw"), blockSize, readOnly)

        /** Cria (ou trunca) um arquivo esparso do tamanho pedido — simula um pendrive vazio. */
        fun create(file: File, sizeBytes: Long, blockSize: Int = 512): FileBlockDevice {
            require(sizeBytes % blockSize == 0L) { "Tamanho deve ser múltiplo de $blockSize" }
            RandomAccessFile(file, "rw").use { it.setLength(0); it.setLength(sizeBytes) }
            return open(file, false, blockSize)
        }
    }
}

/** Dispositivo em memória (para testes pequenos). */
class MemoryBlockDevice(
    val data: ByteArray,
    override val blockSize: Int = 512,
    override val isReadOnly: Boolean = false,
) : BlockDevice {
    init {
        require(data.size % blockSize == 0) { "Tamanho deve ser múltiplo de $blockSize" }
    }

    constructor(sizeBytes: Int, blockSize: Int = 512) : this(ByteArray(sizeBytes), blockSize)

    override val blockCount: Long = (data.size / blockSize).toLong()

    override fun read(lba: Long, buffer: ByteArray, offset: Int, length: Int) {
        checkRange(lba, buffer, offset, length)
        data.copyInto(buffer, offset, (lba * blockSize).toInt(), (lba * blockSize).toInt() + length)
    }

    override fun write(lba: Long, buffer: ByteArray, offset: Int, length: Int) {
        if (isReadOnly) throw ReadOnlyDeviceException()
        checkRange(lba, buffer, offset, length)
        buffer.copyInto(data, (lba * blockSize).toInt(), offset, offset + length)
    }

    override fun flush() = Unit
    override fun close() = Unit
}

/**
 * "Janela" sobre um trecho de outro dispositivo — normalmente uma partição.
 * O setor 0 desta janela corresponde ao setor [startLba] do dispositivo pai.
 * Fechar a janela NÃO fecha o dispositivo pai.
 */
class SubRangeBlockDevice(
    private val parent: BlockDevice,
    val startLba: Long,
    override val blockCount: Long,
) : BlockDevice {
    init {
        require(startLba >= 0 && blockCount >= 0 && startLba + blockCount <= parent.blockCount) {
            "Faixa $startLba+$blockCount fora do dispositivo (${parent.blockCount} setores)"
        }
    }

    override val blockSize: Int get() = parent.blockSize
    override val isReadOnly: Boolean get() = parent.isReadOnly

    override fun read(lba: Long, buffer: ByteArray, offset: Int, length: Int) {
        checkRange(lba, buffer, offset, length)
        parent.read(startLba + lba, buffer, offset, length)
    }

    override fun write(lba: Long, buffer: ByteArray, offset: Int, length: Int) {
        checkRange(lba, buffer, offset, length)
        parent.write(startLba + lba, buffer, offset, length)
    }

    override fun flush() = parent.flush()
    override fun close() = Unit
}

/** Atalho para criar a janela de uma partição. */
fun BlockDevice.slice(startLba: Long, blockCount: Long): BlockDevice =
    SubRangeBlockDevice(this, startLba, blockCount)
