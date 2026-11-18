package dev.bootforge.app.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import dev.bootforge.blockdev.RandomReader
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Lê um arquivo escolhido pelo usuário (seletor do Android) em qualquer posição, SEM copiá-lo.
 * Necessário para analisar a ISO e para ler o install.wim de dentro dela durante a gravação.
 *
 * Usa o descritor de arquivo + FileChannel (leitura posicional). Funciona com arquivos do
 * armazenamento interno e cartão SD; alguns provedores de nuvem não permitem acesso aleatório —
 * nesse caso a abertura falha com uma mensagem clara.
 */
class UriRandomReader private constructor(
    private val pfd: ParcelFileDescriptor,
    private val channel: FileChannel,
) : RandomReader {

    override val size: Long = channel.size()

    override fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
        if (position >= size) return -1
        return channel.read(ByteBuffer.wrap(buffer, offset, minOf(len.toLong(), size - position).toInt()), position)
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { pfd.close() }
    }

    companion object {
        fun open(context: Context, uri: Uri): UriRandomReader {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Não foi possível abrir o arquivo")
            val ch = FileInputStream(pfd.fileDescriptor).channel
            val ok = runCatching { ch.size(); ch.position(0); true }.getOrDefault(false)
            if (!ok) {
                runCatching { pfd.close() }
                throw IOException("Este local não permite leitura direta do arquivo (ex.: nuvem). Baixe a ISO para o armazenamento do celular.")
            }
            return UriRandomReader(pfd, ch)
        }
    }
}
