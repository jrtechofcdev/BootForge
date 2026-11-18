package dev.bootforge.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.bootforge.app.data.AppGraph
import dev.bootforge.app.data.CenterState
import dev.bootforge.app.data.Format
import dev.bootforge.app.data.JobStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Serviço em primeiro plano enquanto houver downloads na fila: o download continua com a tela
 * desligada ou com outro app aberto, e a notificação mostra o progresso real.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, ID, build(AppGraph.downloads.state.value), type)
        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BootForge:download").apply { acquire(12 * 60 * 60 * 1000L) }
        }
        scope.launch {
            AppGraph.downloads.state.collectLatest { st ->
                if (st.activeCount == 0) stopSelf() else getSystemService(NotificationManager::class.java).notify(ID, build(st))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        super.onDestroy()
    }

    private fun build(st: CenterState): Notification {
        val running = st.jobs.firstOrNull { it.status == JobStatus.RUNNING }
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground).setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(true)
            .setContentTitle(running?.let { "Baixando ${it.req.title} ${it.req.version ?: ""}" } ?: "Downloads na fila")
        val p = running?.progress
        if (p != null && p.bytesTotal > 0) {
            b.setProgress(1000, (p.fraction * 1000).toInt(), false)
            b.setContentText("${Format.bytes(p.bytesDone)} de ${Format.bytes(p.bytesTotal)} • ${Format.speed(p.bytesPerSecond)}")
        } else b.setProgress(0, 0, true).setContentText("${st.activeCount} na fila")
        return b.build()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val ID = 2001
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }
        }
    }
}
