package dev.bootforge.app.data

import android.app.Application
import android.os.Build
import android.util.Log
import dev.bootforge.integrity.Diag
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Uma linha do log. */
data class LogLine(val time: Long, val level: Diag.Level, val tag: String, val message: String) {
    fun format(): String = "${STAMP.get()!!.format(Date(time))} ${level.letter}/$tag: $message"

    companion object {
        val STAMP: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US) }
    }
}

/**
 * Captura de logs do BootForge (Etapa 7).
 *
 *  - Guarda em ARQUIVO (pasta privada do app, `logs/`), com rotação: até 5 arquivos de 1 MB,
 *    então o log nunca enche o celular. Sobrevive a travamentos e a reinícios do app.
 *  - Mantém as últimas linhas em memória para a tela "Logs e diagnóstico".
 *  - Repete tudo no logcat do Android (útil com cabo USB e `adb logcat`).
 *  - Grava travamentos (crash) com o rastreamento completo do erro, de forma SÍNCRONA,
 *    para não perder nada quando o app fecha.
 *
 * A gravação em arquivo é feita numa linha de execução própria: registrar um evento nunca
 * deixa a gravação do pendrive mais lenta.
 */
object AppLog : Diag.Sink {
    private const val MAX_FILE = 1L shl 20
    private const val FILES = 5
    private const val MEMORY = 3000

    private lateinit var dir: File
    private val memory = ArrayDeque<LogLine>()
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "bootforge-log").apply { isDaemon = true } }

    val logDir: File get() = dir
    private val current get() = File(dir, "bootforge.log")

    fun install(app: Application) {
        dir = File(app.filesDir, "logs").apply { mkdirs() }
        Diag.verbose = app.getSharedPreferences("logs", 0).getBoolean("verbose", false)
        Diag.sink = this
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { writeCrash(t, e) }
            previous?.uncaughtException(t, e)
        }
        Diag.i("App", "BootForge iniciado — Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
    }

    fun setVerbose(app: Application, on: Boolean) {
        Diag.verbose = on
        app.getSharedPreferences("logs", 0).edit().putBoolean("verbose", on).apply()
        Diag.i("Logs", "Log detalhado ${if (on) "ligado" else "desligado"}")
    }

    override fun log(level: Diag.Level, tag: String, message: String, error: Throwable?) {
        val text = if (error != null) "$message\n${stack(error)}" else message
        val line = LogLine(System.currentTimeMillis(), level, tag, text)
        synchronized(memory) {
            memory.addLast(line)
            while (memory.size > MEMORY) memory.removeFirst()
        }
        val priority = when (level) { Diag.Level.DEBUG -> Log.DEBUG; Diag.Level.INFO -> Log.INFO; Diag.Level.WARN -> Log.WARN; Diag.Level.ERROR -> Log.ERROR }
        runCatching { Log.println(priority, "BootForge/$tag", text) }
        if (::dir.isInitialized) writer.execute { append(line.format() + "\n") }
    }

    /** Linhas recentes (as mais novas por último). */
    fun recent(): List<LogLine> = synchronized(memory) { memory.toList() }

    /** Conteúdo completo dos arquivos de log, do mais antigo para o mais novo. */
    fun fileContents(): String {
        flush()
        return (FILES - 1 downTo 1).map { File(dir, "bootforge.$it.log") }.plus(current)
            .filter { it.exists() }.joinToString("") { it.readText() }
    }

    fun totalBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0

    /** Relatórios de travamento ainda não vistos. */
    fun crashes(): List<File> = dir.listFiles { f -> f.name.startsWith("crash-") }?.sortedBy { it.name } ?: emptyList()

    fun markCrashesSeen() = crashes().forEach { it.renameTo(File(dir, "visto-" + it.name)) }

    fun allCrashReports(): List<File> = dir.listFiles { f -> f.name.startsWith("crash-") || f.name.startsWith("visto-crash-") }?.sortedBy { it.name } ?: emptyList()

    fun clear() {
        flush()
        dir.listFiles()?.forEach { it.delete() }
        synchronized(memory) { memory.clear() }
        Diag.i("Logs", "Logs apagados pelo usuário")
    }

    /** Espera a gravação pendente terminar. */
    fun flush() = runCatching { writer.submit {}.get() }

    private fun append(text: String) {
        runCatching {
            if (current.length() > MAX_FILE) rotate()
            current.appendText(text)
        }
    }

    private fun rotate() {
        File(dir, "bootforge.${FILES - 1}.log").delete()
        for (i in FILES - 2 downTo 1) File(dir, "bootforge.$i.log").takeIf { it.exists() }?.renameTo(File(dir, "bootforge.${i + 1}.log"))
        current.renameTo(File(dir, "bootforge.1.log"))
    }

    private fun writeCrash(t: Thread, e: Throwable) {
        val now = System.currentTimeMillis()
        val text = buildString {
            appendLine("=== TRAVAMENTO (crash) ===")
            appendLine("Quando: ${LogLine.STAMP.get()!!.format(Date(now))}")
            appendLine("Linha de execução: ${t.name}")
            appendLine("Aparelho: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()
            appendLine(stack(e))
            appendLine("=== Últimas linhas do log antes do travamento ===")
            recent().takeLast(200).forEach { appendLine(it.format()) }
        }
        File(dir, "crash-$now.txt").writeText(text) // síncrono: o processo vai terminar
        runCatching { current.appendText(LogLine(now, Diag.Level.ERROR, "Crash", "Travamento: ${e.javaClass.name}: ${e.message}").format() + "\n") }
    }

    fun stack(e: Throwable): String = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString().trimEnd()
}
