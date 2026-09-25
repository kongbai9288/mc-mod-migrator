package com.kongbai.modmigrator

import android.content.Context

/**
 * 收藏夹。
 *
 * 存在用户已授权的工作目录（WorkDir/data/favorites.json）里，
 * 而不是应用私有目录——这样换设备、重装都能带着走，
 * 也符合「用户数据都放在已允许的保存目录」的要求。
 */
object Favorites {

    private const val FILE = "favorites.json"

    /**
     * 写：能写工作目录就写，同时写应用私有目录做兜底。
     *
     * 两处都写的原因：工作目录那份方便换设备带走，
     * 私有目录那份保证"没授权工作目录"时功能依然可用。
     */
    private fun writeText(ctx: Context, txt: String) {
        var wrote = false
        try {
            val dir = WorkDir.data(ctx)
            if (dir != null) {
                val f = dir.findFile(FILE)
                    ?: dir.createFile("application/json", FILE)
                if (f != null) {
                    ctx.contentResolver.openOutputStream(f.uri, "wt")?.use {
                        it.write(txt.toByteArray())
                    }
                    wrote = true
                }
            }
        } catch (t: Throwable) { Err.ignore(t, "写收藏到工作目录") }
        try {
            java.io.File(ctx.filesDir, FILE).writeText(txt)
            wrote = true
        } catch (t: Throwable) { Err.ignore(t, "写收藏到私有目录") }
        if (!wrote) {
            // 这里可能在后台线程被调用。直接 Toast 会抛
            // "Can't toast on a thread that has not called Looper.prepare()"，
            // 所以统一切到主线程再弹。
            LogCenter.e("Favorites", "收藏保存失败：两个位置都写不进去")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    android.widget.Toast.makeText(
                        ctx, "收藏保存失败", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * 读收藏列表。
     *
     * 读工作目录时如果内容解析失败，要**继续试应用私有目录**——
     * 工作目录里那份可能是早期版本写坏的，而私有目录那份还是好的。
     * 之前只认第一份，一旦坏掉就整个收藏夹变空。
     */
    fun list(ctx: Context): List<MarketMod> {
        // 先试工作目录（换设备能带走）
        WorkDir.data(ctx)?.findFile(FILE)?.let { f ->
            val parsed = parseList(readFrom(ctx, f.uri))
            if (parsed != null) return parsed
            LogCenter.w("Favorites", "工作目录里的收藏解析失败，改用本地那份")
        }
        // 再试应用私有目录
        return parseList(runCatching {
            val f = java.io.File(ctx.filesDir, FILE)
            if (f.exists()) f.readText() else null
        }.getOrNull()) ?: emptyList()
    }

    private fun readFrom(ctx: Context, uri: android.net.Uri): String? =
        try {
            ctx.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        } catch (t: Throwable) {
            Err.ignore(t, "读收藏文件")
            null
        }

    /**
     * 解析收藏 JSON。解析不出内容返回 null（让调用方去试下一份），
     * 而不是返回空列表把"文件坏了"和"确实没收藏"混为一谈。
     */
    private fun parseList(txt: String?): List<MarketMod>? {
        if (txt.isNullOrBlank()) return null
        val arr = Json.arr(txt) ?: return null
        val out = mutableListOf<MarketMod>()
        for (e in arr) {
            out.add(
                MarketMod(
                    id = Json.s(e, "id"),
                    slug = Json.s(e, "slug"),
                    name = Json.s(e, "name"),
                    summary = Json.s(e, "summary"),
                    iconUrl = Json.s(e, "iconUrl"),
                    pageUrl = Json.s(e, "pageUrl"),
                    downloads = Json.l(e, "downloads"),
                    source = Json.s(e, "source", "modrinth"),
                    // 这几个字段之前没存：CurseForge 的模组从收藏夹重新加载后
                    // 丢了 fileId/fileName，下载只能退回读秒页；
                    // updated 丢了会导致"按更新时间排序"时收藏项全排最后。
                    fileId = Json.s(e, "fileId"),
                    fileName = Json.s(e, "fileName"),
                    updated = Json.s(e, "updated")
                )
            )
        }
        return out
    }

    /**
     * 写收藏列表。
     *
     * **必须用真正的 JSON 序列化，不能手工拼字符串。**
     * 之前是手写 `esc()` 只转义了反斜杠和引号 —— 没转义换行、制表符等控制字符，
     * 而这些在模组简介里非常常见（Modrinth / CurseForge 的 summary 经常带换行）。
     * 结果是：收藏一个带换行的模组 → favorites.json 变成非法 JSON →
     * 下次 list() 解析失败返回空 → **整个收藏夹凭空消失**。
     * 这正是"收藏点了没反应""收藏夹打不开"的根因。
     */
    private fun save(ctx: Context, items: List<MarketMod>) {
        try {
            val arr = org.json.JSONArray()
            for (m in items) {
                arr.put(
                    org.json.JSONObject().apply {
                        put("id", m.id)
                        put("slug", m.slug)
                        put("name", m.name)
                        put("summary", m.summary)
                        put("iconUrl", m.iconUrl)
                        put("pageUrl", m.pageUrl)
                        put("downloads", m.downloads)
                        put("source", m.source)
                        put("fileId", m.fileId)
                        put("fileName", m.fileName)
                        put("updated", m.updated)
                    }
                )
            }
            writeText(ctx, arr.toString())
        } catch (t: Throwable) {
            LogCenter.e("Favorites", "保存失败：${t.message}")
        }
    }

    fun add(ctx: Context, m: MarketMod): Boolean {
        val list = list(ctx).toMutableList()
        val key = m.id.ifBlank { m.slug }
        if (list.any { (it.id.ifBlank { it.slug }) == key }) return false
        list.add(0, m)
        save(ctx, list)
        return true
    }

    fun remove(ctx: Context, m: MarketMod) {
        val key = m.id.ifBlank { m.slug }
        save(ctx, list(ctx).filter { (it.id.ifBlank { it.slug }) != key })
    }

    fun has(ctx: Context, m: MarketMod): Boolean {
        val key = m.id.ifBlank { m.slug }
        return list(ctx).any { (it.id.ifBlank { it.slug }) == key }
    }

    fun toggle(ctx: Context, m: MarketMod): Boolean {
        return if (has(ctx, m)) {
            remove(ctx, m)
            false
        } else {
            add(ctx, m)
            true
        }
    }

    // 这里原来有个手写的 esc() 用来拼 JSON 字符串，
    // 它只转义了反斜杠和引号，漏掉换行等控制字符，会把收藏文件写坏。
    // 现在改用 org.json 序列化，这个函数已删除，不要再手写 JSON。
}
