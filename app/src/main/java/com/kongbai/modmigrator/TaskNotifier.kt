package com.kongbai.modmigrator

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 后台任务的通知栏进度。
 *
 * 之前只有下载（前台服务）有通知，其它耗时任务 —— 迁移、体检、
 * 区块渲染、导出整合包 —— **切到后台就没有任何反馈**，
 * 回到桌面只看到应用"什么都没干"，切回来才发现还在转。
 *
 * 这里统一挂到 [Progress] 上：任何走了进度中心的任务都会自动显示通知栏进度，
 * 不用每个任务各自写一遍通知。
 *
 * ⚠️ 两个必须注意的点：
 *
 *   1. **不能每次 update 都 notify**。
 *      下载回调一秒能来几十次，每次都 `notify()` 会被系统限流丢弃
 *      （Logcat 里就是 "Package has already posted ... 被抑制"），
 *      严重时直接拖慢主线程。这里按 400ms 节流。
 *   2. **Android 13 起要 POST_NOTIFICATIONS 权限**。
 *      没授权时 `notify()` 不抛异常、只是静默不显示，
 *      所以整体 try 包住，失败不能影响任务本身。
 */
object TaskNotifier {

    private const val ID = 9100
    private const val REQ = 9100
    private const val MIN_INTERVAL = 400L

    private var installed = false
    private var lastAt = 0L
    private var lastTitle = ""
    private var wasRunning = false

    fun install(app: Context) {
        if (installed) return
        installed = true
        Notifier.createChannel(app)
        Progress.addHook { s -> onState(app.applicationContext, s) }
    }

    private fun onState(ctx: Context, s: Progress.State) {
        if (!s.running) {
            // 任务结束：留一条"已完成"在通知栏，几秒后自动撤掉，
            // 不然用户切回来根本不知道刚才跑完了没有。
            if (wasRunning) {
                wasRunning = false
                val title = if (lastTitle.isNotBlank()) "已完成：$lastTitle" else "任务已完成"
                show(ctx, title, "点开查看", 100, false)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    runCatching { NotificationManagerCompat.from(ctx).cancel(ID) }
                }, 5000)
            }
            return
        }
        wasRunning = true
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastAt < MIN_INTERVAL) return
        lastAt = now
        lastTitle = s.label.ifBlank { s.text.ifBlank { "正在处理" } }
        show(ctx, s.text.ifBlank { lastTitle }, s.detail.ifBlank { "进行中" }, s.percent, true)
    }

    private fun show(ctx: Context, title: String, text: String, percent: Int, ongoing: Boolean) {
        runCatching {
            val flags = if (android.os.Build.VERSION.SDK_INT >= 23) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getActivity(
                ctx, REQ,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                flags
            )
            val nb = NotificationCompat.Builder(ctx, Notifier.CHANNEL)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setPriority(NotificationCompat.PRIORITY_LOW)

            // total <= 0 时 percent 恒为 0，这时用不确定进度条，
            // 否则会一直显示一条空进度条，看着像卡住了
            if (percent > 0) nb.setProgress(100, percent, false)
            else nb.setProgress(100, 0, true)

            NotificationManagerCompat.from(ctx).notify(ID, nb.build())
        }
    }
}
