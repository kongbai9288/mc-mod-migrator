package com.kongbai.modmigrator

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * 游戏目录（.minecraft）—— **可以有多个**。
 *
 * ## 为什么不能只存一个
 *
 * 手机上装两三个启动器是很常见的事：FCL 一个实例目录、
 * Zalith 又一个、Pojav 还留着旧存档。而每个启动器的 `.minecraft`
 * 是**各自独立**的 —— 模组装进 A 的目录，B 里一个都加载不到。
 *
 * 之前 `K.GAME_DIR` 只存一个字符串，于是：
 *   - 授权第二个目录会把第一个**覆盖掉**，第一个的读写权限也就再也拿不回来
 *   - 迁移扫描只认那一个目录，别的启动器下的模组"凭空消失"
 *   - 用户在设置里看到"已设置"，但指的到底是哪个目录，只有代码知道
 *
 * 现在存成一份列表，授权是**追加**不是覆盖，权限各自独立保留。
 *
 * ## 权限怎么保住
 *
 * SAF 的 `takePersistableUriPermission` 每次授权都会新增一条系统记录，
 * 系统上限约 128 条。所以每授权一个目录就调一次，
 * 移除时对应 release 掉 —— 不会因为"后来又授权了一个"把前面的挤掉。
 */
object GameDirs {

    data class Entry(
        val name: String,
        val uri: String
    ) {
        fun label(): String = name.ifBlank { Uri.parse(uri).lastPathSegment ?: uri }
    }

    private const val LIST = "game_dirs"
    private const val CUR = "game_dir_cur"

    /** 已授权的全部目录 */
    fun list(ctx: Context): List<Entry> {
        val raw = Prefs.get(ctx).getString(LIST, null)
        // 老版本只存了一个，这里读出来补进列表，用户的旧设置不会丢
        if (raw.isNullOrBlank()) {
            val legacy = Prefs.get(ctx).getString(K.GAME_DIR, null)
            if (legacy.isNullOrBlank()) return emptyList()
            val name = Prefs.get(ctx).getString(K.GAME_DIR_NAME, null).orEmpty()
            val e = Entry(name, legacy)
            save(ctx, listOf(e))
            return listOf(e)
        }
        return runCatching {
            val a = JSONArray(raw)
            val out = ArrayList<Entry>(a.length())
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val uri = o.optString("uri")
                if (uri.isBlank()) continue
                out.add(Entry(o.optString("name"), uri))
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun save(ctx: Context, items: List<Entry>) {
        val a = JSONArray()
        for (e in items) {
            a.put(JSONObject().apply {
                put("name", e.name)
                put("uri", e.uri)
            })
        }
        Prefs.get(ctx).edit().putString(LIST, a.toString()).apply()
    }

    /**
     * 追加一个目录。已存在就只把它设为当前。
     *
     * @return 是否新加了（false 表示已经有这个目录了）
     */
    fun add(ctx: Context, uri: Uri): Boolean {
        grant(ctx, uri)
        val s = uri.toString()
        val items = list(ctx).toMutableList()
        if (items.any { it.uri == s }) {
            setCurrent(ctx, s)
            return false
        }
        items.add(Entry(readable(s), s))
        save(ctx, items)
        setCurrent(ctx, s)
        // 同时写回老键，老代码（还在读 K.GAME_DIR 的地方）也能拿到
        Prefs.get(ctx).edit()
            .putString(K.GAME_DIR, s)
            .putString(K.GAME_DIR_NAME, readable(s))
            .apply()
        return true
    }

    /** 移除一个目录，并把它的授权释放掉 */
    fun remove(ctx: Context, uri: String) {
        val items = list(ctx).toMutableList()
        val at = items.indexOfFirst { it.uri == uri }
        if (at < 0) return
        items.removeAt(at)
        save(ctx, items)
        release(ctx, uri)
        val cur = current(ctx)
        if (cur == null || cur.uri == uri) {
            setCurrent(ctx, items.firstOrNull()?.uri ?: "")
        }
        if (items.isEmpty()) {
            Prefs.get(ctx).edit()
                .remove(K.GAME_DIR).remove(K.GAME_DIR_NAME).apply()
        } else {
            val first = items.first()
            Prefs.get(ctx).edit()
                .putString(K.GAME_DIR, first.uri)
                .putString(K.GAME_DIR_NAME, first.name)
                .apply()
        }
    }

    /** 当前正在用的那个。没设就返回列表第一个 */
    fun current(ctx: Context): Entry? {
        val items = list(ctx)
        if (items.isEmpty()) return null
        val cur = Prefs.get(ctx).getString(CUR, "").orEmpty()
        return items.firstOrNull { it.uri == cur } ?: items.first()
    }

    fun setCurrent(ctx: Context, uri: String) {
        Prefs.get(ctx).edit().putString(CUR, uri).apply()
        val e = list(ctx).firstOrNull { it.uri == uri } ?: return
        Prefs.get(ctx).edit()
            .putString(K.GAME_DIR, uri)
            .putString(K.GAME_DIR_NAME, e.name)
            .apply()
    }

    /**
     * 授权并持久保留。
     *
     * 必须先 takePersistableUriPermission 再存 ——
     * 只存字符串不申请权限的话，重开应用照样没权限读写，
     * 表现为"设置里显示已选，功能里却找不到目录"。
     */
    fun grant(ctx: Context, uri: Uri) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    private fun release(ctx: Context, uriStr: String) {
        runCatching {
            val u = Uri.parse(uriStr)
            ctx.contentResolver.releasePersistableUriPermission(
                u,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    /** 已授权的数量，给设置页显示 */
    fun size(ctx: Context): Int = list(ctx).size

    /** SAF Uri 长得没法读，只取最后一段给用户看 */
    fun readable(s: String): String {
        val i = s.lastIndexOf('%')
        val tail = if (i >= 0) s.substring(i + 1) else s.substringAfterLast('/')
        return tail.ifBlank { s }
    }
}
