package dev.bootforge.integrity

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Algoritmos de hash suportados para verificação de integridade.
 *
 * Usamos apenas algoritmos considerados seguros para checagem de arquivos
 * (SHA-256 e SHA-512), que são os publicados pelas distribuições oficiais.
 */
enum class HashAlgorithm(val javaName: String, val hexLength: Int, val label: String = javaName) {
    // MD5 e SHA-1 servem só para CONFERIR checksums antigos publicados por alguns sites;
    // não são seguros contra adulteração intencional (o app avisa).
    MD5("MD5", 32),
    SHA1("SHA-1", 40),
    SHA256("SHA-256", 64),
    SHA512("SHA-512", 128);

    /** Resistente a adulteração intencional (MD5 e SHA-1 não são). */
    val secure: Boolean get() = this == SHA256 || this == SHA512

    fun newDigest(): MessageDigest = MessageDigest.getInstance(javaName)

    companion object {
        /** Deduz o algoritmo pelo tamanho do hash em hexadecimal. */
        fun fromHexLength(length: Int): HashAlgorithm? = entries.firstOrNull { it.hexLength == length }
    }
}

/** Funções utilitárias de hash em streaming (não carregam o arquivo inteiro na memória). */
object Hashing {

    private const val BUFFER_SIZE = 1 shl 20 // 1 MiB

    /**
     * Calcula o hash de um [InputStream] até o fim.
     * @param onProgress chamado com o total de bytes já processados.
     */
    fun hash(
        input: InputStream,
        algorithm: HashAlgorithm = HashAlgorithm.SHA256,
        cancel: CancellationSignal = CancellationSignal.NONE,
        onProgress: (Long) -> Unit = {},
    ): String {
        val digest = algorithm.newDigest()
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            cancel.throwIfCancelled()
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
            total += n
            onProgress(total)
        }
        return digest.digest().toHex()
    }

    fun hash(file: File, algorithm: HashAlgorithm = HashAlgorithm.SHA256): String =
        file.inputStream().use { hash(it, algorithm) }

    fun hash(bytes: ByteArray, algorithm: HashAlgorithm = HashAlgorithm.SHA256): String =
        algorithm.newDigest().digest(bytes).toHex()

    /** Compara dois hashes em hexadecimal ignorando maiúsculas/minúsculas e espaços. */
    fun matches(expected: String, actual: String): Boolean =
        expected.trim().equals(actual.trim(), ignoreCase = true)
}

fun ByteArray.toHex(): String {
    val chars = "0123456789abcdef"
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(chars[v ushr 4]).append(chars[v and 0x0F])
    }
    return sb.toString()
}

/**
 * Sinal de cancelamento cooperativo, compartilhado entre a interface e as operações longas.
 * A operação verifica [isCancelled] entre blocos e interrompe com [OperationCancelledException].
 */
open class CancellationSignal {
    @Volatile
    var isCancelled: Boolean = false
        private set

    open fun cancel() {
        isCancelled = true
    }

    fun throwIfCancelled() {
        if (isCancelled) throw OperationCancelledException()
    }

    companion object {
        /** Sinal que nunca é cancelado (para chamadas que não precisam de cancelamento). */
        val NONE = object : CancellationSignal() {
            override fun cancel() = Unit
        }
    }
}

class OperationCancelledException : RuntimeException("Operação cancelada pelo usuário")
