package dev.bootforge.integrity

/**
 * Registro de diagnóstico (log) usado por todos os módulos.
 *
 * O núcleo não sabe ONDE o log é guardado: o app instala um [sink] que grava em arquivo
 * (ver AppLog no módulo app). Nos testes e na linha de comando o padrão é não fazer nada.
 *
 * Regras: nunca registrar dados pessoais (conteúdo de arquivos, contas, senhas); números de
 * série de pendrives são mascarados por quem chama.
 */
object Diag {
    enum class Level(val letter: Char) { DEBUG('D'), INFO('I'), WARN('W'), ERROR('E') }

    fun interface Sink {
        fun log(level: Level, tag: String, message: String, error: Throwable?)
    }

    @Volatile
    var sink: Sink = Sink { _, _, _, _ -> }

    /** Log detalhado (ex.: cada comando SCSI). Desligado por padrão: deixa o app mais lento. */
    @Volatile
    var verbose: Boolean = false

    fun d(tag: String, message: () -> String) { if (verbose) sink.log(Level.DEBUG, tag, message(), null) }
    fun i(tag: String, message: String) = sink.log(Level.INFO, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = sink.log(Level.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = sink.log(Level.ERROR, tag, message, error)

    /** Mascara identificadores (ex.: número de série "4C530001234" → "•••••••1234"). */
    fun mask(s: String?): String? = s?.let { if (it.length <= 4) "••••" else "•".repeat(it.length - 4) + it.takeLast(4) }
}
