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
import dev.bootforge.app.data.Format
import dev.bootforge.app.data.OperationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Serviço em primeiro plano durante operações longas (gravar ISO, copiar arquivos, instalar).
 * Mantém o app ativo mesmo que o usuário troque de app ou desligue a tela, mostra uma
 * notificação com o progresso REAL e segura um WakeLock parcial (CPU ligada).
 */
class OperationService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val initial = build(AppGraph.operations.state.value)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, initial, type)
        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BootForge:operacao").apply { acquire(6 * 60 * 60 * 1000L) }
        }
        scope.launch {
            AppGraph.operations.state.collectLatest { st ->
                val nm = getSystemService(NotificationManager::class.java)
                if (st == null || st.status != OperationState.Status.RUNNING) {
                    if (st != null) nm.notify(DONE_ID, build(st))
                    stopSelf()
                } else nm.notify(NOTIFICATION_ID, build(st))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        super.onDestroy()
    }

    private fun build(st: OperationState?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setContentTitle(st?.title ?: "BootForge")
        when (st?.status) {
            OperationState.Status.RUNNING, null -> {
                val p = st?.progress
                b.setOngoing(true).setContentText(st?.step ?: "Preparando…")
                if (p != null && p.bytesTotal > 0) {
                    b.setProgress(1000, (p.fraction * 1000).toInt(), false)
                    b.setSubText("${(p.fraction * 100).toInt()}% • ${Format.speed(p.bytesPerSecond)}")
                } else b.setProgress(0, 0, true)
            }
            OperationState.Status.SUCCESS -> b.setContentText("Concluído: ${st.result}").setAutoCancel(true)
            OperationState.Status.FAILED -> b.setContentText("FALHOU: ${st.result}").setAutoCancel(true)
            OperationState.Status.CANCELLED -> b.setContentText("Cancelado").setAutoCancel(true)
        }
        return b.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Operações em andamento", NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object {
        private const val CHANNEL = "operacoes"
        private const val NOTIFICATION_ID = 1001
        private const val DONE_ID = 1002

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, OperationService::class.java)) }
        }
    }
}
