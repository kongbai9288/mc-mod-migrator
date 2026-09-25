package com.kongbai.modmigrator

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // 统一日志：之前各处是静默 catch + 零散 println，
        // 出问题完全查不到。Timber 会把日志接到崩溃报告里一起带出来。
        runCatching {
            // 用 applicationInfo.flags 判断可调试，而不是 BuildConfig.DEBUG：
            // BuildConfig 由各模块各自生成，这里引用的是 app 模块的类，
            // 但写在了被其它地方共用的位置，容易解析不到。
            val debuggable =
                (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            if (debuggable) {
                timber.log.Timber.plant(timber.log.Timber.DebugTree())
            }
            timber.log.Timber.plant(CrashTree())
        }
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
}

/**
 * 把 Timber 的日志同时写进崩溃日志缓冲。
 * 这样用户分享崩溃日志时，能看到崩之前发生了什么，
 * 而不只是最后那一行堆栈。
 */
private class CrashTree : timber.log.Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val p = when (priority) {
            android.util.Log.ERROR -> "E"
            android.util.Log.WARN -> "W"
            else -> "I"
        }
        runCatching {
            val line = "$p/${tag ?: "app"}: $message"
            when (p) {
                "E" -> LogCenter.e(tag ?: "app", message)
                "W" -> LogCenter.w(tag ?: "app", message)
                else -> LogCenter.i(tag ?: "app", message)
            }
            if (t != null) LogCenter.e(tag ?: "app", t.message ?: t.toString())
        }
    }
}
