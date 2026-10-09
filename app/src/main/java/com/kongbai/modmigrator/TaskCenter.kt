package com.kongbai.modmigrator

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

/**
 * 后台任务的通知栏进程。
 *
 * # 为什么要有这个
 *
 * 扫描上千个模组、批量下载、上传网盘这类活儿都要跑几十秒到几分钟。
 * 之前界面一划走就**完全没动静** —— 回到桌面不知道它还在不在跑，
 * 想看进度只能把应用切回前台，而切回去的过程本身就可能把它打断。
 *
 * 所以凡是长任务：起一条常驻通知，带进度和百分比，跑完再变成果通知。
 *
 * 常驻（ongoing）是刻意的：用户划不掉，避免手一滑把正在跑的任务通知清了，
 * 误以为任务也没了。结束时会显式解除常驻。
 */
object TaskCenter {

    private val live = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()
    private var seq = 4000

    /** 起一条任务通知。返回 id，后续用它更新和结束 */
    fun start(ctx: Context, title: String, text: String = ""): Int {
        val id = seq++
        live[id] = true
        push(ctx, id, title, text, -1, -1, true, false) {}
        return id
    }

    /** 更新进度。total<=0 走不确定进度（转圈） */
    fun progress(ctx: Context, id: Int, title: String, text: String, cur: Int, total: Int) {
        if (live[id] != true) return
        push(ctx, id, title, text, cur, total, true, false) {}
        // 同步应用内卡片，两边显示一致
        InAppNotice.progress(id, cur, total, text)
    }

    /**
     * 任务结束。
     * @param keep true = 留一条结果通知（用户不在前台时有用）；
     *             false = 直接撤掉，结果只显示在应用内
     */
    fun finish(
        ctx: Context, id: Int, title: String, text: String, success: Boolean, keep: Boolean = false
    ) {
        live.remove(id)
        if (keep) {
            push(ctx, id, title, text, -1, -1, false, false) {}
        } else {
            runCatching {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(id)
            }
        }
        InAppNotice.done(id, title, text, success)
    }

    /** 中途取消：撤通知 + 撤卡片 */
    fun cancel(ctx: Context, id: Int) {
        live.remove(id)
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(id)
        }
        InAppNotice.dismiss(id)
    }

    private fun push(
        ctx: Context, id: Int, title: String, text: String,
        cur: Int, total: Int, ongoing: Boolean, done: Boolean,
        block: (NotificationCompat.Builder) -> Unit
    ) {
        try {
            val b = NotificationCompat.Builder(ctx, Notifier.CHANNEL)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setPriority(
                    if (ongoing) NotificationCompat.PRIORITY_LOW
                    else NotificationCompat.PRIORITY_DEFAULT
                )
            if (total > 0) {
                b.setProgress(total, cur.coerceIn(0, total), false)
            } else if (ongoing) {
                b.setProgress(0, 0, true)
            }
            block(b)
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(id, b.build())
        } catch (t: Throwable) {
            // Android 13 起没授予通知权限会抛异常，不能因此中断任务本身
            Err.ignore(t, "任务通知")
        }
    }
}
