package com.kongbai.modmigrator

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 流量统计与提醒。
 *
 * 只在**移动网络**下计数（WiFi 不心疼）。
 * 累计超过阈值就提示一次，提示后重置计数，避免一直弹。
 *
 * 计数点：所有经过 Http 的请求（Http.get / Http.call）会调 record()。
 */
object Traffic {

    /** 默认阈值：200MB */
    fun thresholdMb(ctx: Context): Int =
        Prefs.get(ctx).getInt(K.TRAFFIC_WARN_MB, 200).coerceIn(10, 100_000)

    fun setThresholdMb(ctx: Context, mb: Int) {
        Prefs.get(ctx).edit()
            .putInt(K.TRAFFIC_WARN_MB, mb.coerceIn(10, 100_000)).apply()
    }

    /** 当前是否是移动网络 */
    fun isMetered(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as? ConnectivityManager ?: return false
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                    ?: return false
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            } else {
                @Suppress("DEPRECATION")
                cm.activeNetworkInfo?.type == ConnectivityManager.TYPE_MOBILE
            }
        } catch (t: Throwable) {
            false
        }
    }

    /** 已用字节（自上次提醒起） */
    fun used(ctx: Context): Long = Prefs.get(ctx).getLong("traffic_used", 0L)

    /** 总计（不清零，给用户看） */
    fun total(ctx: Context): Long = Prefs.get(ctx).getLong("traffic_total", 0L)

    /**
     * 记录一次流量。
     * @return 如果本次导致超过阈值，返回 true（调用方负责提示）
     */
    fun record(ctx: Context, bytes: Long): Boolean {
        if (bytes <= 0) return false
        try {
            val p = Prefs.get(ctx)
            val total = p.getLong("traffic_total", 0L) + bytes
            val used = p.getLong("traffic_used", 0L) + bytes
            p.edit()
                .putLong("traffic_total", total)
                .putLong("traffic_used", used)
                .apply()
            val limit = thresholdMb(ctx) * 1024L * 1024L
            if (isMetered(ctx) && used >= limit) {
                // 提示后清零，下次再攒够才提示
                p.edit().putLong("traffic_used", 0L).apply()
                return true
            }
        } catch (t: Throwable) {
        }
        return false
    }

    /** 手动清零 */
    fun reset(ctx: Context) {
        Prefs.get(ctx).edit()
            .putLong("traffic_used", 0L)
            .putLong("traffic_total", 0L)
            .apply()
    }

    /** 给用户看的描述 */
    fun describe(ctx: Context): String {
        val mb = { b: Long -> String.format("%.1f MB", b / 1048576.0) }
        return "移动网络下累计：${mb(used(ctx))} / 阈值 ${thresholdMb(ctx)} MB\n" +
            "历史总计：${mb(total(ctx))}\n" +
            "当前网络：${if (isMetered(ctx)) "移动数据" else "WiFi 或不计费"}"
    }
}
