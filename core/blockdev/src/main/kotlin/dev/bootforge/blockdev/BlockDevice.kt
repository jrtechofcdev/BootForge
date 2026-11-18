package dev.bootforge.blockdev

import java.io.Closeable
import java.io.IOException

/**
 * Dispositivo de blocos: qualquer coisa que se lê e grava por setores (LBA).
 *
 * É a peça central do BootForge. A mesma lógica (partições, exFAT, instalação do Ventoy)
 * funciona igualmente sobre:
 *  - um pendrive real conectado por USB OTG (implementação no módulo `android:usb`);
 *  - um arquivo de imagem de disco no PC ([FileBlockDevice]) — usado nos testes;
 *  - um trecho de outro dispositivo, como uma partição ([SubRangeBlockDevice]).
 *
 * Regras:
 *  - `lba` é o número do setor lógico (0 = primeiro setor do disco);
 *  - `length` deve ser múltiplo de [blockSize];
 *  - erros de E/S geram [IOException] (nunca falham em silêncio).
 */
interface BlockDevice : Closeable {
    /** Tamanho do setor lógico em bytes (geralmente 512). */
    val blockSize: Int

    /** Quantidade total de setores. */
    val blockCount: Long

    /** true quando o dispositivo foi aberto apenas para leitura. */
    val isReadOnly: Boolean

    /** Capacidade total em bytes. */
    val sizeBytes: Long get() = blockSize.toLong() * blockCount

    @Throws(IOException::class)
    fun read(lba: Long, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size)

    @Throws(IOException::class)
    fun write(lba: Long, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size)

    /** Garante que os dados gravados chegaram fisicamente ao dispositivo. */
    @Throws(IOException::class)
    fun flush()
}

/** Exceção específica para gravação em dispositivo somente leitura. */
class ReadOnlyDeviceException : IOException("O dispositivo foi aberto somente para leitura")

/** Validações comuns usadas pelas implementações. */
internal fun BlockDevice.checkRange(lba: Long, buffer: ByteArray, offset: Int, length: Int) {
    require(length % blockSize == 0) { "Tamanho $length não é múltiplo do setor ($blockSize)" }
    require(offset >= 0 && offset + length <= buffer.size) { "Faixa inválida no buffer" }
    val count = length / blockSize
    if (lba < 0 || lba + count > blockCount) {
        throw IOException("Acesso fora do dispositivo: LBA $lba + $count setores (total $blockCount)")
    }
}

/** Lê [count] setores a partir de [lba] e devolve um novo array. */
fun BlockDevice.readBlocks(lba: Long, count: Int): ByteArray {
    val buf = ByteArray(count * blockSize)
    read(lba, buf)
    return buf
}

/**
 * Lê bytes em uma posição arbitrária (não alinhada a setor).
 * Útil para estruturas pequenas, como um campo dentro do MBR.
 */
fun BlockDevice.readBytes(byteOffset: Long, length: Int): ByteArray {
    val bs = blockSize.toLong()
    val firstLba = byteOffset / bs
    val lastLba = (byteOffset + length - 1) / bs
    val raw = readBlocks(firstLba, (lastLba - firstLba + 1).toInt())
    val start = (byteOffset - firstLba * bs).toInt()
    return raw.copyOfRange(start, start + length)
}

/**
 * Grava bytes em posição arbitrária usando leitura-modificação-escrita
 * (lê os setores afetados, altera somente os bytes pedidos e grava de volta).
 */
fun BlockDevice.writeBytes(byteOffset: Long, data: ByteArray) {
    if (data.isEmpty()) return
    val bs = blockSize.toLong()
    val firstLba = byteOffset / bs
    val lastLba = (byteOffset + data.size - 1) / bs
    val raw = readBlocks(firstLba, (lastLba - firstLba + 1).toInt())
    val start = (byteOffset - firstLba * bs).toInt()
    data.copyInto(raw, start)
    write(firstLba, raw)
}

/** Preenche [count] setores com zeros, em blocos de até 1 MiB. */
fun BlockDevice.zeroBlocks(lba: Long, count: Long) {
    val chunkBlocks = maxOf(1, (1 shl 20) / blockSize)
    val zeros = ByteArray(chunkBlocks * blockSize)
    var done = 0L
    while (done < count) {
        val n = minOf(chunkBlocks.toLong(), count - done).toInt()
        write(lba + done, zeros, 0, n * blockSize)
        done += n
    }
}
