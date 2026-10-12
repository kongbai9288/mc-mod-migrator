package com.kongbai.modmigrator

import android.content.Context
import android.net.Uri
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 客户端请求限速。按域名各自计数，避免把某个平台打爆。
 */
object NetGate {

    /**
     * 联网用途。每一项对应一个断网分项开关，见 Prefs.K 的 OFFLINE_*。
     */
    enum class Area(val key: String) {
        SEARCH(K.OFFLINE_SEARCH),
        TRANSLATE(K.OFFLINE_TRANSLATE),
        FEED(K.OFFLINE_FEED),
        UPDATE(K.OFFLINE_UPDATE),
        ANNOUNCE(K.OFFLINE_ANNOUNCE),
    }

    const val SLOW = 60
    const val NORMAL = 120
    const val FAST = 240

    private class Bucket {
        var tokens = 0.0
        var last = 0L
    }

    private val buckets = ConcurrentHashMap<String, Bucket>()

    /**
     * 该用途现在能不能联网。总开关打开时一律断网。
     */
    fun allow(ctx: Context?, area: Area): Boolean {
        val c = ctx ?: Prefs.appCtx() ?: return false
        val p = Prefs.get(c)
        if (p.getBoolean(K.OFFLINE, false)) return false
        return !p.getBoolean(area.key, false)
    }

    fun perMin(): Int {
        val ctx = Prefs.appCtx() ?: return NORMAL
        return Prefs.get(ctx).getInt(K.NET_LIMIT, NORMAL).coerceIn(SLOW, FAST)
    }

    fun setPerMin(v: Int) {
        val ctx = Prefs.appCtx() ?: return
        Prefs.get(ctx).edit().putInt(K.NET_LIMIT, v.coerceIn(SLOW, FAST)).apply()
        buckets.clear()
    }

    fun host(url: String): String {
        val h = runCatching { Uri.parse(url).host }.getOrNull()
        return (h ?: "unknown").lowercase(Locale.ROOT)
    }

    fun acquire(url: String) {
        val pm = perMin().toDouble()
        val rate = pm / 60.0
        val cap = (pm / 5.0).coerceAtLeast(6.0)
        val b = buckets.getOrPut(host(url)) { Bucket().also { it.last = System.nanoTime(); it.tokens = cap } }
        while (true) {
            var wait = 0L
            synchronized(b) {
                val now = System.nanoTime()
                val dt = (now - b.last) / 1_000_000_000.0
                b.last = now
                b.tokens = (b.tokens + dt * rate).coerceAtMost(cap)
                if (b.tokens >= 1.0) {
                    b.tokens -= 1.0
                } else {
                    wait = (((1.0 - b.tokens) / rate) * 1000.0).toLong() + 5
                }
            }
            if (wait <= 0L) return
            try {
                Thread.sleep(wait.coerceAtMost(2000L))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    fun reset() {
        buckets.clear()
    }
}
