package com.kongbai.modmigrator

import android.content.Context
import java.util.Locale

/**
 * 聚合搜索 + 自动推荐。
 *
 * 聚合：按设置把 Modrinth / CurseForge / 后端的结果合并，按来源去重
 *      （同一个模组在两个平台都有时，保留下载量高的那个）。
 * 推荐：根据当前实例的 MC 版本与加载器，拉热门模组并过滤掉已安装的。
 */
object AggregateSearch {

    fun search(ctx: Context, q: String, mc: String, loader: String): List<MarketMod> {
        val p = Prefs.get(ctx)
        val mode = p.getString(K.SOURCE, "聚合") ?: "聚合"
        val agg = p.getBoolean(K.AGG_SEARCH, true)
        val offline = p.getBoolean(K.OFFLINE, false)
        if (offline) return emptyList()

        val out = LinkedHashMap<String, MarketMod>()

        fun merge(list: List<MarketMod>) {
            for (m in list) {
                val key = norm(m.name)
                if (key.isBlank()) continue
                val old = out[key]
                if (old == null || m.downloads > old.downloads) out[key] = m
            }
        }

        // CurseForge 有两条路：后端代理（内置 Key）与官方直连（自己填 Key）。
        // 单源模式下二者互斥——开一个另一个自动关（UI 层联动）。
        // 但「聚合」模式不受互斥影响：两条路都问，结果合并去重，谁都搜得到。
        val useBackend = p.getBoolean(K.USE_BACKEND, true)
        val useOfficial = p.getBoolean(K.USE_OFFICIAL_CF, false)
        val aggregate = mode == "聚合"

        val wantModrinth = aggregate || mode == "Modrinth"
        val wantBackend = aggregate && useBackend || mode == "后端" && useBackend
        val wantCf = (aggregate && useOfficial) ||
            (mode == "CurseForge" && (useOfficial || !useBackend))

        if (wantModrinth) {
            runCatching { merge(ModrinthApi.search(q, mc, loader, 20)) }
        }
        if (wantCf) {
            val key = p.getString(K.CF_KEY, "") ?: ""
            runCatching { merge(CurseForgeApi.search(q, mc, loader, key, 20)) }
        }
        if (wantBackend) {
            runCatching { merge(BackendApi.search(ctx, q, mc, loader, 0, 20)) }
        }

        return out.values.sortedByDescending { it.downloads }
    }

    /**
     * 自动推荐：拉当前版本的热门模组，去掉已安装的。
     * installedNames 来自当前实例 mods 目录的文件名。
     */
    fun recommend(ctx: Context, mc: String, loader: String, installed: Set<String>): List<MarketMod> {
        if (Prefs.get(ctx).getBoolean(K.OFFLINE, false)) return emptyList()
        if (!Prefs.get(ctx).getBoolean(K.RECOMMEND, true)) return emptyList()
        if (mc.isBlank()) return emptyList()

        val out = LinkedHashMap<String, MarketMod>()
        fun merge(list: List<MarketMod>) {
            for (m in list) {
                val key = norm(m.name)
                if (key.isBlank()) continue
                if (installed.any { norm(it).contains(key) || key.contains(norm(it)) }) continue
                val old = out[key]
                if (old == null || m.downloads > old.downloads) out[key] = m
            }
        }
        // 用空关键词 + 按下载量排序，等价于拉热门榜
        runCatching { merge(ModrinthApi.search("", mc, loader, 30)) }
        runCatching { merge(CurseForgeApi.search("", mc, loader, Prefs.get(ctx).getString(K.CF_KEY, "") ?: "", 30)) }
        return out.values.sortedByDescending { it.downloads }.take(20)
    }

    private fun norm(s: String): String =
        s.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\u4e00-\\u9fa5]"), "")
}
