package ru.mintgram.app

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Держит процесс живым и микрофон доступным, пока идёт звонок (сворачивание приложения его не прерывает). */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifier.ensureChannel(this)
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, Notifier.ONGOING_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Идёт звонок")
            .setContentText("MintGram · нажмите, чтобы вернуться")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        try {
            ServiceCompat.startForeground(
                this, 7002, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
        } catch (_: Exception) {
            stopSelf() // нет разрешения на микрофон или запуск из фона — звонок продолжится без сервиса
        }
        return START_NOT_STICKY
    }

    companion object {
        fun start(ctx: Context) {
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, CallService::class.java)) } catch (_: Exception) {}
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, CallService::class.java)) } catch (_: Exception) {}
        }
    }
}
