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

    /** 最近一次搜索走了哪几条路，给界面提示用 */
    @Volatile
    var lastRoutes: String = ""
        private set

    private fun logCf(s: String) {
        lastRoutes = if (lastRoutes.isBlank()) s else "$lastRoutes；$s"
    }

    fun search(ctx: Context, q: String, mc: String, loader: String): List<MarketMod> {
        val p = Prefs.get(ctx)
        val mode = p.getString(K.SOURCE, "聚合") ?: "聚合"
        val agg = p.getBoolean(K.AGG_SEARCH, true)
        val offline = p.getBoolean(K.OFFLINE, false)
        if (offline) return emptyList()

        lastRoutes = ""
        val out = LinkedHashMap<String, MarketMod>()

        fun merge(list: List<MarketMod>) {
            for (m in list) {
                val key = norm(m.name)
                if (key.isBlank()) continue
                val old = out[key]
                if (old == null || m.downloads > old.downloads) out[key] = m
            }
        }

        // CurseForge 有三条可达路径：
        //   1. 后端代理 —— 内置 Key，需要后端在线
        //   2. 官方直连 —— 需要自己填 Key
        //   3. 国内镜像 —— 不需要 Key，无 Key 时是唯一可行路径
        // 「聚合」模式：Modrinth + CurseForge 都要问；CurseForge 优先后端，
        //   后端不可用就退到镜像（没 Key）或官方（有 Key 且开了官方直连）。
        val useBackend = p.getBoolean(K.USE_BACKEND, true)
        val useOfficial = p.getBoolean(K.USE_OFFICIAL_CF, false)
        val key = p.getString(K.CF_KEY, "") ?: ""
        val aggregate = mode == "聚合"

        var gotCf = false

        // Modrinth
        if (aggregate || mode == "Modrinth") {
            runCatching { merge(ModrinthApi.search(q, mc, loader, 20)) }
        }

        // CurseForge：后端优先
        if ((aggregate && useBackend) || (mode == "后端" && useBackend)) {
            val r = runCatching { BackendApi.search(ctx, q, mc, loader, 0, 20) }.getOrNull()
            if (!r.isNullOrEmpty()) {
                merge(r)
                gotCf = true
            } else {
                logCf("后端没有返回 CurseForge 结果，改用其他路径")
            }
        }

        // CurseForge：后端没结果就走镜像或官方
        if (!gotCf && (aggregate || mode == "CurseForge" || mode == "后端")) {
            val canOfficial = useOfficial && key.isNotBlank()
            val r = runCatching {
                CurseForgeApi.search(q, mc, loader, if (canOfficial) key else "", 20)
            }.getOrNull()
            if (!r.isNullOrEmpty()) {
                merge(r)
                gotCf = true
                logCf(if (key.isNotBlank() && useOfficial) "CurseForge 走了官方直连" else "CurseForge 走了国内镜像")
            }
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

        lastRoutes = ""
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
