package io.github.mcardosor.candango

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

/**
 * Avisos do Candango: notificação de "ônibus chegando" / "desça na próxima" e o serviço em primeiro plano
 * que mantém o GPS e as atualizações rodando com a tela apagada, enquanto há viagem ou aviso ativo.
 */
object Notifier {
    private const val CH_ALERTS = "avisos"
    private const val CH_TRIP = "viagem"
    const val ONGOING_ID = 1
    private var nextId = 100

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, "Avisos de ônibus", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Ônibus chegando no ponto e hora de descer"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 150, 300, 150, 300)
        })
        nm.createNotificationChannel(NotificationChannel(CH_TRIP, "Viagem em andamento", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Acompanhamento da viagem e dos avisos ligados"
            setShowBadge(false)
        })
    }

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun warn(ctx: Context, title: String, body: String) {
        if (!canNotify(ctx)) return
        val n = Notification.Builder(ctx, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(nextId++, n)
    }

    fun ongoing(ctx: Context, title: String, body: String): Notification =
        Notification.Builder(ctx, CH_TRIP)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .build()

    fun updateOngoing(ctx: Context, title: String, body: String) {
        if (!canNotify(ctx)) return
        ctx.getSystemService(NotificationManager::class.java).notify(ONGOING_ID, ongoing(ctx, title, body))
    }
}

class TripService : Service() {
    companion object {
        private const val EXTRA_TITLE = "titulo"
        private const val EXTRA_BODY = "texto"
        var running = false
            private set

        private fun hasLocation(ctx: Context) =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** Liga o serviço (só dá para ligar com o app aberto, que é quando a pessoa toca em "Iniciar viagem" ou no sino). */
        fun start(ctx: Context, title: String, body: String) {
            if (running) { Notifier.updateOngoing(ctx, title, body); return }
            // o tipo "location" exige a permissão de localização; sem ela os avisos funcionam só com o app aberto
            if (!hasLocation(ctx)) return
            runCatching {
                ContextCompat.startForegroundService(ctx, Intent(ctx, TripService::class.java).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_BODY, body))
            }
        }

        fun stop(ctx: Context) {
            if (running) ctx.stopService(Intent(ctx, TripService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notifier.ongoing(this, intent?.getStringExtra(EXTRA_TITLE) ?: "Candango", intent?.getStringExtra(EXTRA_BODY) ?: "")
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(Notifier.ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(Notifier.ONGOING_ID, n)
            running = true
        } catch (_: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
