package com.kongbai.modmigrator

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object Store {

    private lateinit var ctx: Context

    fun init(c: Context) {
        ctx = c.applicationContext
    }

    fun deviceId(c: Context): String {
        val p = Prefs.get(c)
        var id = p.getString(K.DEVICE_ID, null)
        if (id.isNullOrBlank()) {
            id = UUID.randomUUID().toString().substring(0, 8)
            p.edit().putString(K.DEVICE_ID, id).apply()
        }
        return id
    }

    fun deviceLabel(c: Context): String {
        val p = Prefs.get(c)
        var l = p.getString(K.DEVICE_LABEL, null)
        if (l.isNullOrBlank()) {
            l = "${Build.MANUFACTURER} ${Build.MODEL}"
            p.edit().putString(K.DEVICE_LABEL, l).apply()
        }
        return l
    }

    private fun file(c: Context): File = File(c.filesDir, "marked_links.json")

    /**
     * 专用的单线程 IO 执行器。
     *
     * 标记链接的读写全都是磁盘操作，而调用方分布在
     * MarketFragment、ModPageActivity、WebActivity 三处，
     * 其中 `addLink` 是在**长按链接时直接调**的，就在主线程上。
     * 文件小的时候感觉不出来，但存储卡顿（尤其低端机、或目录在外置 SD 卡上）
     * 时一次写入就可能几十毫秒到几百毫秒，连续操作直接 ANR。
     *
     * 与其要求每个调用方都记得切后台（一定会有人忘），
     * 不如在 Store 内部把写入强制挪到后台线程，
     * 调用方完全不用改，行为也不变（仍是同步返回结果）。
     */
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** 在当前线程（已保证是后台）执行磁盘写，返回结果 */
    private fun <T> onIo(block: () -> T): T {
        // 已经在后台线程就直接跑，避免无谓的线程切换与嵌套提交
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            return block()
        }
        return try {
            // ⚠️ 必须显式包成 Callable。
            // kotlin 的 () -> T 传给 ExecutorService.submit 时，
            // 在 Runnable 与 Callable 两个重载之间无法自动判定，
            // 直接写 `io.submit(block)` 编译不过。
            io.submit(java.util.concurrent.Callable { block() }).get()
        } catch (t: Throwable) {
            Err.fail(t, "Store 后台 IO 失败")
            throw t
        }
    }

    fun links(c: Context): MutableList<MarkedLink> {
        val f = file(c)
        val out = mutableListOf<MarkedLink>()
        if (!f.exists()) return out
        // 读同样是磁盘 IO，一并挪到后台
        return onIo {
            try {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    out.add(
                        MarkedLink(o.optString("title", o.optString("url")), o.optString("url"))
                    )
                }
                out
            } catch (t: Throwable) {
                out
            }
        }
    }

    /**
     * 保存标记链接。
     *
     * 之前这里没有 try，而 `writeText` 是**磁盘写入**，磁盘满/权限变化时会抛异常。
     * 调用方 `Store.addLink()` 又是在**主线程**（长按链接直接调）里执行的，
     * 一抛就是未捕获异常 → 应用直接崩。
     * 改成返回是否成功，让调用方能如实告知用户"没存上"。
     *
     * @return 是否写入成功
     */
    fun saveLinks(c: Context, list: List<MarkedLink>): Boolean {
        // 强制在后台线程写盘（见 [io] 的说明）
        return onIo {
            try {
                val arr = JSONArray()
                for (l in list) {
                    val o = JSONObject()
                    o.put("title", l.title)
                    o.put("url", l.url)
                    arr.put(o)
                }
                // 先写临时文件再原子改名：直接 writeText 会先截断原文件，
                // 写到一半失败就剩一个空文件，之前存的链接全没了。
                val target = file(c)
                val tmp = File(target.parentFile, target.name + ".tmp")
                tmp.writeText(arr.toString())
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                true
            } catch (t: Throwable) {
                Err.ignore(t, "保存标记链接")
                false
            }
        }
    }

    fun addLink(c: Context, link: MarkedLink): Boolean {
        val list = links(c)
        if (list.any { it.url == link.url }) return false
        list.add(0, link)
        return saveLinks(c, list)
    }

    fun removeLink(c: Context, url: String) {
        val list = links(c).filter { it.url != url }
        saveLinks(c, list)
    }

    /**
     * 记录"上次看到哪儿"。
     *
     * 分享时带上这个位置，收到的人（或你另一台设备）打开就能直接
     * 回到同一个地方，不用从头翻。存的是路径/链接 + 标题 + 时间。
     */
    fun markViewed(ctx: android.content.Context, key: String, title: String) {
        try {
            Prefs.get(ctx).edit()
                .putString(K.LAST_VIEW_KEY, key)
                .putString(K.LAST_VIEW_TITLE, title)
                .putLong(K.LAST_VIEW_AT, System.currentTimeMillis())
                .apply()
        } catch (t: Throwable) { Err.ignore(t, ".apply()") }
    }

    /** 上次查看的位置，没有则返回 null */
    fun lastViewed(ctx: android.content.Context): Triple<String, String, Long>? {
        return try {
            val p = Prefs.get(ctx)
            val k = p.getString(K.LAST_VIEW_KEY, "") ?: ""
            if (k.isBlank()) return null
            Triple(k, p.getString(K.LAST_VIEW_TITLE, "") ?: "", p.getLong(K.LAST_VIEW_AT, 0L))
        } catch (t: Throwable) {
            null
        }
    }

    fun clearViewed(ctx: android.content.Context) {
        try {
            Prefs.get(ctx).edit()
                .remove(K.LAST_VIEW_KEY).remove(K.LAST_VIEW_TITLE).remove(K.LAST_VIEW_AT)
                .apply()
        } catch (t: Throwable) { Err.ignore(t, ".apply()") }
    }

    fun clear(c: Context) {
        file(c).delete()
    }
}
