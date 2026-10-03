package com.kongbai.modmigrator

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

object Notifier {

    const val CHANNEL = "modmigrator"
    private var seq = 2000

    /** 创建通知渠道。Android 8.0 起不发通知必须先建渠道，否则通知根本不显示。 */
    fun createChannel(ctx: Context) {
        try {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
            val channel = android.app.NotificationChannel(
                CHANNEL,
                ctx.getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        } catch (t: Throwable) {
            Err.ignore(t, "创建通知渠道")
        }
    }

    fun show(ctx: Context, title: String, text: String) {
        try {
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(seq++, n)
        } catch (t: Throwable) {
            // ignore
                 Err.ignore(t, "ignore")
             }
    }
}
