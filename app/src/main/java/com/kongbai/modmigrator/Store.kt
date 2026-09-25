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

    fun links(c: Context): MutableList<MarkedLink> {
        val f = file(c)
        val out = mutableListOf<MarkedLink>()
        if (!f.exists()) return out
        return try {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(MarkedLink(o.optString("title", o.optString("url")), o.optString("url")))
            }
            out
        } catch (t: Throwable) {
            out
        }
    }

    fun saveLinks(c: Context, list: List<MarkedLink>) {
        val arr = JSONArray()
        for (l in list) {
            val o = JSONObject()
            o.put("title", l.title)
            o.put("url", l.url)
            arr.put(o)
        }
        file(c).writeText(arr.toString())
    }

    fun addLink(c: Context, link: MarkedLink): Boolean {
        val list = links(c)
        if (list.any { it.url == link.url }) return false
        list.add(0, link)
        saveLinks(c, list)
        return true
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
