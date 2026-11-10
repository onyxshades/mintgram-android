package ru.mintgram.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

object Notifier {
    private const val CHANNEL = "messages"
    const val CALL_CHANNEL = "calls"
    const val ONGOING_CHANNEL = "call_ongoing"
    private const val CALL_ID = 7001

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Сообщения", NotificationManager.IMPORTANCE_HIGH)
            )
            // мелодию играет само приложение, поэтому у канала звука нет
            nm.createNotificationChannel(
                NotificationChannel(CALL_CHANNEL, "Входящие звонки", NotificationManager.IMPORTANCE_HIGH)
                    .apply { setSound(null, null); enableVibration(false) }
            )
            nm.createNotificationChannel(
                NotificationChannel(ONGOING_CHANNEL, "Идущий звонок", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun allowed(ctx: Context) =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun message(ctx: Context, title: String, text: String, chatKey: String) {
        if (!allowed(ctx)) return
        val intent = Intent(ctx, MainActivity::class.java)
            .putExtra("chat", chatKey)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            ctx, chatKey.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(chatKey.hashCode(), n)
    }

    fun cancel(ctx: Context, chatKey: String) {
        ctx.getSystemService(NotificationManager::class.java).cancel(chatKey.hashCode())
    }

    fun incomingCall(ctx: Context, from: String, video: Boolean) {
        if (!allowed(ctx)) return
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            ctx, CALL_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CALL_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(from)
            .setContentText(if (video) "Входящий видеозвонок" else "Входящий звонок")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setTimeoutAfter(45_000)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(CALL_ID, n)
    }

    fun cancelCall(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(CALL_ID)
    }
}
