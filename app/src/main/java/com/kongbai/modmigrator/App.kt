package com.kongbai.modmigrator

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        Prefs.init(this)
        // 主题必须在任何 Activity 创建前定好，否则深色模式要重启才生效
        runCatching { ThemePrefs.init(this) }
        Store.init(this)
        // 预热 CookieManager，避免首次登录时初始化卡顿
        runCatching { WebCookies.warmUp() }
        runCatching { LangPack.load(this) }
        runCatching { UpdateWorker.schedule(this) }
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


