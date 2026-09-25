package com.kongbai.modmigrator

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

class App : Application() {

    /**
     * 给 Coil 的**全局默认** ImageLoader 注册解码器。
     *
     * 为什么必须在这里注册：
     * 界面里大量用的是 `imageView.load(url)` 这种扩展函数，它走的是
     * Coil 的**全局单例 loader**，不是我们另外 new 的那个。
     * 之前只在一个自建 ImageLoader 上注册了 SVG/GIF 解码器，
     * 于是列表里的 `.load()` 拿不到解码器 ——
     *   · Modrinth 有些图标是 SVG → 解码失败 → 只剩首字母占位
     *   · 有些模组用的是 **GIF 动图图标** → 没有 GifDecoder → 不动或显示不出
     * 现在全局注册，所有 `.load()` 都生效。
     *
     * 注意：本项目用的是 Coil **2.x**，全局单例通过 `Coil.setImageLoader()`
     * 设置（`SingletonImageLoader` 是 Coil 3.x 的 API，这里用不了）。
     */
    private fun installCoil() {
        try {
            val loader = coil.ImageLoader.Builder(this)
                .components {
                    // SVG：Modrinth 图标有相当一部分是矢量图
                    add(coil.decode.SvgDecoder.Factory())
                    // GIF：部分模组用动图当图标
                    // API 28+ 用系统的 ImageDecoder（支持动图且更省内存），
                    // 低版本用 Coil 自带的 GifDecoder。
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        add(coil.decode.ImageDecoderDecoder.Factory())
                    } else {
                        add(coil.decode.GifDecoder.Factory())
                    }
                }
                .crossfade(true)
                .build()
            coil.Coil.setImageLoader(loader)
        } catch (t: Throwable) {
            // 注册失败也不能拖垮启动，退化为 Coil 默认 loader
            timber.log.Timber.w(t, "全局图片解码器注册失败")
        }
    }

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
        // 必须在任何界面创建前设置好，否则先加载的图片拿不到解码器
        installCoil()
        CrashHandler.install(this)
        Prefs.init(this)
        // 主题必须在任何 Activity 创建前定好，否则深色模式要重启才生效
        runCatching { ThemePrefs.init(this) }
        Store.init(this)
        // 开机/启动后重新注册自动备份。
        // WorkManager 的任务在重启后虽然会恢复，但用户改过间隔或地址后
        // 需要按最新设置重新 enqueue，这里统一兜一次。
        runCatching { CloudBackup.schedule(this) }
        // 预热 CookieManager + 真实 WebView 实例。
        // 只调 getInstance() 不够，很多设备上必须先创建过 WebView，
        // cookie 存储才会真正可用，否则登录时 state cookie 写不进去。
        runCatching { WebCookies.warmUp(this) }
        runCatching { LangPack.load(this) }

        // 按用户设定的保留天数清理过期回收站。
        // 之前 purgeExpired() 只在**打开回收站页面时**手动触发一次——
        // 用户在设置里选了"保留 7 天"，但从不打开回收站页的话，
        // 过期文件会一直堆着不清理，这个设置等于没生效。
        // 改成每次启动后台清一次（清单很小，不影响启动速度）。
        Thread {
            runCatching { Trash.purgeExpired(this@App) }
        }.start()

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
