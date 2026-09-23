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

    private fun file(ctx: Context) = WorkDir.data(ctx)?.findFile(FILE)

    private fun ensure(ctx: Context) =
        file(ctx) ?: WorkDir.data(ctx)?.createFile("application/json", FILE)

    fun list(ctx: Context): List<MarketMod> {
        return try {
            val f = file(ctx) ?: return emptyList()
            val txt = ctx.contentResolver.openInputStream(f.uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: return emptyList()
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
            val f = ensure(ctx) ?: return
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
            ctx.contentResolver.openOutputStream(f.uri, "wt")?.use {
                it.write(sb.toString().toByteArray())
            }
        } catch (t: Throwable) {
            // 存不进去也不该崩
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
