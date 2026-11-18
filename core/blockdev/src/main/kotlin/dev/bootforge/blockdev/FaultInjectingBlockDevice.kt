package dev.bootforge.blockdev

import java.io.IOException

/**
 * Dispositivo para TESTES DE ESTABILIDADE: simula o pendrive sendo arrancado (ou parando de
 * responder) depois de [failAfterWrittenBytes] bytes gravados — ou na leitura, com
 * [failAfterReadBytes]. A partir daí, toda operação falha, como num pendrive desconectado.
 *
 * Os dados gravados ANTES da falha ficam no dispositivo de baixo ([inner]), então dá para
 * "reconectar" (usar o [inner]) e conferir o que sobrou.
 */
class FaultInjectingBlockDevice(
    val inner: BlockDevice,
    private val failAfterWrittenBytes: Long = Long.MAX_VALUE,
    private val failAfterReadBytes: Long = Long.MAX_VALUE,
) : BlockDevice {
    override val blockSize: Int get() = inner.blockSize
    override val blockCount: Long get() = inner.blockCount
    override val isReadOnly: Boolean get() = inner.isReadOnly

    var written = 0L; private set
    var read = 0L; private set
    @Volatile var unplugged = false; private set

    override fun read(lba: Long, buffer: ByteArray, offset: Int, length: Int) {
        check()
        if (read + length > failAfterReadBytes) { unplugged = true; check() }
        inner.read(lba, buffer, offset, length)
        read += length
    }

    override fun write(lba: Long, buffer: ByteArray, offset: Int, length: Int) {
        check()
        if (written + length > failAfterWrittenBytes) {
            // grava só a parte que "deu tempo" (como um pendrive arrancado no meio do comando)
            val partial = ((failAfterWrittenBytes - written) / blockSize * blockSize).toInt()
            if (partial > 0) inner.write(lba, buffer, offset, partial)
            written += partial
            unplugged = true
            check()
        }
        inner.write(lba, buffer, offset, length)
        written += length
    }

    override fun flush() { check(); inner.flush() }
    override fun close() = Unit

    private fun check() { if (unplugged) throw IOException("Dispositivo USB desconectado (simulação de teste)") }
}
