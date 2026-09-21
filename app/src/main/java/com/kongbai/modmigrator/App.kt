package com.kongbai.modmigrator

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        Prefs.init(this)
        Store.init(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Notifier.CHANNEL,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun installCrashHandler() {
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val f = java.io.File(filesDir, "crash.log")
                val w = java.io.FileWriter(f, true)
                val ts = java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    java.util.Locale.CHINA
                ).format(java.util.Date())
                w.append(ts).append("  ").append(t.name).append('\n')
                w.append(e.toString()).append('\n')
                for (st in e.stackTrace.take(25)) {
                    w.append("    at ").append(st.toString()).append('\n')
                }
                w.flush()
                w.close()
            } catch (ignore: Throwable) {
            }
            old?.uncaughtException(t, e)
        }
    }
}
