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
     * 读：优先读工作目录（换设备能带走），读不到再读应用私有目录。
     * 之前只认工作目录，用户没设工作目录时 list() 直接返回空、
     * add() 静默失败——表现就是「收藏点了没反应」。
     */
    private fun readText(ctx: Context): String? {
        WorkDir.data(ctx)?.findFile(FILE)?.let { f ->
            try {
                ctx.contentResolver.openInputStream(f.uri)?.use {
                    return it.readBytes().toString(Charsets.UTF_8)
                }
            } catch (t: Throwable) { Err.ignore(t, "return it.readBytes().toString(Charsets.UTF_8)") }
        }
        return try {
            val f = java.io.File(ctx.filesDir, FILE)
            if (f.exists()) f.readText() else null
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 写：能写工作目录就两个地方都写；写不了就至少保证本地可用。
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
        } catch (t: Throwable) { Err.ignore(t, "") }
        try {
            java.io.File(ctx.filesDir, FILE).writeText(txt)
            wrote = true
        } catch (t: Throwable) { Err.ignore(t, "wrote = true") }
        if (!wrote) {
            android.widget.Toast.makeText(ctx, "收藏保存失败", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun list(ctx: Context): List<MarketMod> {
        return try {
            val txt = readText(ctx) ?: return emptyList()
            val arr = Json.arr(txt) ?: return emptyList()
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
                        source = Json.s(e, "source", "modrinth")
                    )
                )
            }
            out
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun save(ctx: Context, items: List<MarketMod>) {
        try {
            val sb = StringBuilder("[")
            items.forEachIndexed { i, m ->
                if (i > 0) sb.append(",")
                sb.append("{")
                    .append("\"id\":\"").append(esc(m.id)).append("\",")
                    .append("\"slug\":\"").append(esc(m.slug)).append("\",")
                    .append("\"name\":\"").append(esc(m.name)).append("\",")
                    .append("\"summary\":\"").append(esc(m.summary)).append("\",")
                    .append("\"iconUrl\":\"").append(esc(m.iconUrl)).append("\",")
                    .append("\"pageUrl\":\"").append(esc(m.pageUrl)).append("\",")
                    .append("\"downloads\":").append(m.downloads).append(",")
                    .append("\"source\":\"").append(esc(m.source)).append("\"")
                    .append("}")
            }
            sb.append("]")
            writeText(ctx, sb.toString())
        } catch (t: Throwable) {
            // 存不进去也不该崩
                 Err.ignore(t, "存不进去也不该崩")
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

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
