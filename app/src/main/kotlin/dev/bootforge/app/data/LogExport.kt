package dev.bootforge.app.data

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import androidx.core.content.FileProvider
import dev.bootforge.integrity.Diag
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Monta o RELATÓRIO DE DIAGNÓSTICO (um único .txt) para o usuário salvar ou compartilhar:
 * aparelho, versão do app, pendrives conectados, travamentos, histórico de operações,
 * downloads, o log do app e o logcat do próprio processo.
 *
 * Privacidade: não inclui conteúdo de arquivos, contas ou e-mails; números de série dos
 * pendrives aparecem mascarados (só os 4 últimos dígitos).
 */
object LogExport {

    fun build(context: Context): String = buildString {
        val now = Date()
        appendLine("==================== BootForge — relatório de diagnóstico ====================")
        appendLine("Gerado em: ${SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("pt", "BR")).format(now)}")
        val pi = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        @Suppress("DEPRECATION")
        appendLine("App: BootForge ${pi?.versionName} (código ${pi?.versionCode})")
        appendLine("Aparelho: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Arquiteturas: ${Build.SUPPORTED_ABIS.joinToString()}")
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        appendLine("Memória: total ${mem.totalMem / 1048576} MB, livre ${mem.availMem / 1048576} MB${if (mem.lowMemory) " (POUCA MEMÓRIA)" else ""}")
        appendLine("Armazenamento livre: interno ${context.filesDir.usableSpace / 1048576} MB, área de downloads ${runCatching { AppGraph.downloads.dir.usableSpace / 1048576 }.getOrNull()} MB")
        appendLine("Suporte a USB Host (OTG): ${context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)}")
        appendLine("Ventoy embutido: ${runCatching { AppGraph.embeddedPackage.version }.getOrDefault("?")}")
        appendLine("Log detalhado (SCSI): ${if (Diag.verbose) "ligado" else "desligado"}")
        appendLine()

        section("Dispositivos USB conectados agora")
        runCatching { AppGraph.drives.describeAll() }.getOrDefault(emptyList()).ifEmpty { listOf("(nenhum)") }.forEach { appendLine(it) }
        appendLine()

        section("Travamentos (crashes)")
        val crashes = AppLog.allCrashReports()
        if (crashes.isEmpty()) appendLine("(nenhum registrado)")
        crashes.takeLast(3).forEach { appendLine(it.readText()); appendLine() }

        section("Operação atual")
        AppGraph.operations.state.value?.let { op ->
            appendLine("${op.title} — ${op.status} — etapa: ${op.step} — ${op.result ?: ""}")
            op.logs.takeLast(30).forEach { appendLine("  $it") }
        } ?: appendLine("(nenhuma)")
        appendLine()

        section("Histórico de operações (últimas 50)")
        AppGraph.history.entries.value.take(50).forEach { h ->
            appendLine("${SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(Date(h.timestamp))} ${if (h.success) "OK " else "ERRO"} ${h.operation} [${h.device}] ${h.details}")
        }
        appendLine()

        section("Downloads")
        runCatching {
            val st = AppGraph.downloads.state.value
            appendLine("Catálogo: ${st.catalogOrigin}")
            st.jobs.forEach { appendLine("Fila: ${it.req.fileName} ${it.status} ${it.bytes} bytes ${it.message ?: ""}") }
            st.library.forEach { appendLine("Biblioteca: ${it.fileName} ${it.sizeBytes} bytes ${it.status.name}") }
            st.entries.values.filter { it.error != null }.forEach { appendLine("Consulta com erro: ${it.system.id}: ${it.error}") }
        }
        appendLine()

        section("Log do app")
        val log = AppLog.fileContents()
        // limite para caber em WhatsApp/e-mail: as partes mais recentes interessam mais
        appendLine(if (log.length > MAX_LOG) "(início omitido)\n" + log.takeLast(MAX_LOG) else log.ifEmpty { "(vazio)" })
        appendLine()

        section("Logcat do processo (últimas linhas)")
        appendLine(logcat())
        appendLine("==================== fim do relatório ====================")
    }

    /** Grava o relatório num arquivo da pasta de compartilhamento e devolve o arquivo. */
    fun writeFile(context: Context): File {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles { f -> f.name.startsWith("bootforge-log-") }?.forEach { it.delete() }
        val f = File(dir, "bootforge-log-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt")
        f.writeText(build(context))
        Diag.i("Logs", "Relatório gerado: ${f.name} (${f.length()} bytes)")
        return f
    }

    /** Intent de compartilhamento (WhatsApp, e-mail, Drive…) com o relatório anexado. */
    fun shareIntent(context: Context, file: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "BootForge — relatório de diagnóstico")
            putExtra(Intent.EXTRA_TEXT, "Relatório de diagnóstico do BootForge em anexo (${file.name}).")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Enviar relatório do BootForge").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Logcat apenas do processo do app (não precisa de permissão especial). */
    private fun logcat(): String = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "1500", "--pid=${Process.myPid()}").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(5, TimeUnit.SECONDS)
        out.ifBlank { "(vazio)" }
    }.getOrElse { "(indisponível: ${it.message})" }

    private fun StringBuilder.section(t: String) = appendLine("---------- $t ----------")

    private const val MAX_LOG = 1_500_000
}
