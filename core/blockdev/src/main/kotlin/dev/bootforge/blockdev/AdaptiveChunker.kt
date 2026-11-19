package dev.bootforge.blockdev

import java.io.IOException

/**
 * Divide leituras/gravações grandes em comandos USB menores e se ADAPTA ao pendrive.
 *
 * Por quê: alguns controladores baratos recusam comandos grandes (no Galaxy A16 do primeiro teste
 * real, um pendrive recusou 1 MB por comando e travou). Windows e Linux usam 64–120 KB por
 * comando. Aqui começamos com [initialBytes] (64 KB por padrão) e, se um comando falhar:
 *  1. chamamos [recover] (reinicia a conexão USB e o protocolo do pendrive);
 *  2. reduzimos o tamanho pela metade (até [minBytes]) e repetimos o MESMO trecho;
 *  3. se falhar mesmo no menor tamanho, desistimos com uma mensagem clara.
 * O tamanho que funcionou fica valendo para o resto da sessão.
 */
class AdaptiveChunker(initialBytes: Int, private val blockSize: Int, private val minBytes: Int = 4096) {

    /** Tamanho atual de cada comando (múltiplo do setor). */
    @Volatile
    var chunkBytes: Int = align(initialBytes)
        private set

    /** Quantas recuperações já foram feitas nesta sessão. */
    var recoveries = 0
        private set

    /**
     * @param op executa um comando: (setor inicial, deslocamento no buffer, bytes)
     * @param recover reinicia a comunicação depois de uma falha
     * @param onShrink avisa (para o log) que o tamanho foi reduzido
     */
    fun run(
        lba: Long, length: Int,
        op: (at: Long, offset: Int, bytes: Int) -> Unit,
        recover: () -> Unit,
        onShrink: (newBytes: Int, cause: Throwable) -> Unit = { _, _ -> },
    ) {
        var done = 0
        var attemptsHere = 0
        while (done < length) {
            val n = minOf(chunkBytes, length - done)
            val at = lba + done / blockSize
            try {
                op(at, done, n)
                done += n
                attemptsHere = 0
            } catch (e: Exception) {
                if (e is dev.bootforge.integrity.OperationCancelledException) throw e
                attemptsHere++
                // no menor tamanho, só uma nova tentativa; depois disso desiste
                if (chunkBytes <= minBytes && attemptsHere > 1 || recoveries >= MAX_RECOVERIES) throw unresponsive(e)
                try { recover() } catch (r: Exception) { e.addSuppressed(r); throw unresponsive(e) }
                recoveries++
                if (chunkBytes > minBytes) {
                    chunkBytes = maxOf(align(minBytes), align(chunkBytes / 2))
                    onShrink(chunkBytes, e)
                }
            }
        }
    }

    private fun align(bytes: Int) = maxOf(blockSize, bytes / blockSize * blockSize)

    companion object {
        const val DEFAULT_BYTES = 64 * 1024
        private const val MAX_RECOVERIES = 8

        fun unresponsive(cause: Throwable) = IOException(
            "O pendrive parou de responder ao comando USB, mesmo com blocos menores. Desconecte e conecte o pendrive de novo e tente outra vez. " +
                "Se repetir, tente outro adaptador OTG ou outro pendrive e envie o log.",
            cause,
        )
    }
}
