package com.kongbai.modmigrator

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

object Notifier {

    const val CHANNEL = "modmigrator"
    private var seq = 2000

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
