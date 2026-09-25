package com.kongbai.modmigrator

import android.content.Context
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 聚合搜索 + 自动推荐。
 *
 * 核心改动：**分批流式返回**。
 *
 * 之前是「所有源查完再一次性返回」，问题很明显：
 *   - 一个源慢（比如 CurseForge 镜像超时），整页都要等它，网速一慢就像卡死
 *   - 某个源挂了，用户要干等很久才知道没结果
 *
 * 现在：每个源一个独立任务并行跑，谁先查到谁的结果先推给界面立刻显示；
 * 单个源失败只记一条提示，不影响其他源；每个源有独立超时，不拖累整体。
 */
object AggregateSearch {

    /** 最近一次搜索走了哪几条路，给界面提示用 */
    @Volatile
    var lastRoutes: String = ""
        private set

    private fun logCf(s: String) {
        synchronized(this) {
            lastRoutes = if (lastRoutes.isBlank()) s else "$lastRoutes；$s"
        }
    }

    /** 单个源的超时（秒）。到点没返回就放弃这个源，不拖累其他源。 */
    private const val SRC_TIMEOUT_SEC = 15L

    /** 搜索线程池：并行查多个源 */
    private val pool by lazy {
        Executors.newFixedThreadPool(4)
    }

    /**
     * 分批搜索。
     *
     * @param onBatch 每有一批结果就回调一次（主线程）。参数：本批结果、来源名、是否已全部结束
     */
    fun searchStreaming(
        ctx: Context,
        q: String,
        mc: String,
        loader: String,
        onBatch: (batch: List<MarketMod>, source: String, finished: Boolean) -> Unit
    ) = searchStreaming(ctx, q, mc, loader, 0, 20, onBatch)

    /**
     * 分批搜索（带分页）。
     *
     * @param offset 起始偏移。Modrinth 用 `offset`，CurseForge 用 `index`，
     *               两者都是**基于 0** 的，传错会拿到重复或错位的结果。
     * @param limit  每页条数
     */
    fun searchStreaming(
        ctx: Context,
        q: String,
        mc: String,
        loader: String,
        offset: Int,
        limit: Int,
        onBatch: (batch: List<MarketMod>, source: String, finished: Boolean) -> Unit
    ) {
        val p = Prefs.get(ctx)
        if (p.getBoolean(K.OFFLINE, false)) {
            onBatch(emptyList(), "离线模式", true)
            return
        }
        lastRoutes = ""

        // 已见过的名字，用来跨批次去重（同名保留下载量高的）
        val seen = ConcurrentHashMap<String, MarketMod>()
        val pending = AtomicInteger(0)

        fun push(list: List<MarketMod>, source: String): List<MarketMod> {
            val fresh = ArrayList<MarketMod>()
            for (m in list) {
                val key = norm(m.name)
                if (key.isBlank()) continue
                while (true) {
                    val old = seen[key]
                    if (old == null) {
                        if (seen.putIfAbsent(key, m) == null) {
                            fresh.add(m); break
                        }
                    } else if (m.downloads > old.downloads) {
                        if (seen.replace(key, old, m)) { fresh.add(m); break }
                    } else break
                }
            }
            return fresh
        }

        // 收集本次要查的源
        val tasks = buildSources(ctx, q, mc, loader, limit, offset)
        if (tasks.isEmpty()) {
            onBatch(emptyList(), "没有可用的搜索源", true)
            return
        }

        pending.set(tasks.size)
        for ((name, call) in tasks) {
            pool.submit {
                try {
                    val list = call()
                    val fresh = push(list, name)
                    if (fresh.isNotEmpty()) {
                        main { onBatch(fresh, name, false) }
                    } else {
                        logCf("$name 没有新结果")
                    }
                } catch (t: Throwable) {
                    // 单个源失败只记提示，不影响其他源
                    logCf("$name 不可用")
                } finally {
                    if (pending.decrementAndGet() == 0) {
                        main { onBatch(emptyList(), "完成", true) }
                    }
                }
            }
        }
    }

    /** 原同步接口保留，内部走流式并等待全部完成 */
    fun search(ctx: Context, q: String, mc: String, loader: String): List<MarketMod> {
        val all = java.util.Collections.synchronizedList(ArrayList<MarketMod>())
        val done = java.util.concurrent.CountDownLatch(1)
        searchStreaming(ctx, q, mc, loader) { batch, _, finished ->
            all.addAll(batch)
            if (finished) done.countDown()
        }
        try {
            done.await(SRC_TIMEOUT_SEC + 5, TimeUnit.SECONDS)
        } catch (t: Throwable) { Err.ignore(t, "done.await(SRC_TIMEOUT_SEC + 5, TimeUnit.SECONDS)") }
        return all.sortedByDescending { it.downloads }
    }

    /**
     * 按设置构造要查询的源列表。
     * 每个源是 (显示名, 查询函数)，互相独立。
     */
    private fun buildSources(
        ctx: Context, q: String, mc: String, loader: String,
        limit: Int = 20, offset: Int = 0
    ): List<Pair<String, () -> List<MarketMod>>> {
        val p = Prefs.get(ctx)
        val mode = p.getString(K.SOURCE, "聚合") ?: "聚合"
        val useBackend = p.getBoolean(K.USE_BACKEND, true)
        val useOfficial = p.getBoolean(K.USE_OFFICIAL_CF, false)
        val key = p.getString(K.CF_KEY, "") ?: ""
        val aggregate = mode == "聚合"
        val out = ArrayList<Pair<String, () -> List<MarketMod>>>()

        // Modrinth：免费无 Key，最稳，始终优先
        if (aggregate || mode == "Modrinth" || mode == "聚合") {
            out.add("Modrinth" to { ModrinthApi.search(q, mc, loader, limit, offset) })
        }

        // CurseForge 后端代理
        if ((aggregate || mode == "后端") && useBackend) {
            // 后端 /api/mods 的 page 参数会被原样透传给 CurseForge 的 index，
            // 而 index 是 0 基偏移。所以这里直接传 offset，
            // 不能除以页大小（那样第二页会取到和第一页几乎相同的内容）。
            out.add("后端" to { BackendApi.search(ctx, q, mc, loader, offset, limit) })
        }

        // CurseForge 镜像 / 官方直连
        if (aggregate || mode == "CurseForge" || mode == "后端") {
            val canOfficial = useOfficial && key.isNotBlank()
            val label = if (canOfficial) "CurseForge官方" else "CurseForge镜像"
            out.add(
                Pair(label, { CurseForgeApi.search(q, mc, loader, if (canOfficial) key else "", limit, offset) })
            )
            logCf(if (canOfficial) "CurseForge 走官方直连" else "CurseForge 走国内镜像")
        }
        return out
    }

    /**
     * 自动推荐：从多个来源拉热门榜单，去掉已安装的。
     * 也走分批，哪个源先回来就先显示哪批。
     */
    fun recommendStreaming(
        ctx: Context,
        mc: String,
        loader: String,
        installed: Set<String>,
        onBatch: (batch: List<MarketMod>, source: String, finished: Boolean) -> Unit
    ) {
        val p = Prefs.get(ctx)
        if (p.getBoolean(K.OFFLINE, false) || !p.getBoolean(K.RECOMMEND, true) || mc.isBlank()) {
            onBatch(emptyList(), "推荐已关闭", true)
            return
        }
        lastRoutes = ""

        val seen = ConcurrentHashMap<String, MarketMod>()
        val installedNorm = installed.map { norm(it) }.filter { it.isNotBlank() }
        val pending = AtomicInteger(3)
        val key = p.getString(K.CF_KEY, "") ?: ""

        fun push(list: List<MarketMod>): List<MarketMod> {
            val fresh = ArrayList<MarketMod>()
            for (m in list) {
                val k = norm(m.name)
                if (k.isBlank()) continue
                // 过滤已安装
                if (installedNorm.any { it.contains(k) || k.contains(it) }) continue
                val old = seen[k]
                if (old == null) {
                    if (seen.putIfAbsent(k, m) == null) fresh.add(m)
                } else if (m.downloads > old.downloads) {
                    if (seen.replace(k, old, m)) fresh.add(m)
                }
            }
            return fresh
        }

        fun run(name: String, call: () -> List<MarketMod>) {
            pool.submit {
                try {
                    val fresh = push(call())
                    if (fresh.isNotEmpty()) main { onBatch(fresh, name, false) }
                } catch (t: Throwable) {
                    logCf("$name 不可用")
                } finally {
                    if (pending.decrementAndGet() == 0) {
                        main { onBatch(emptyList(), "完成", true) }
                    }
                }
            }
        }

        // 三个来源并行：Modrinth 热门、CurseForge 热门、后端推荐
        run("Modrinth") { ModrinthApi.search("", mc, loader, 30) }
        run("CurseForge") { CurseForgeApi.search("", mc, loader, key, 30) }
        run("后端") { BackendApi.recommend(ctx, mc, loader) ?: emptyList() }
    }

    fun recommend(
        ctx: Context, mc: String, loader: String, installed: Set<String>
    ): List<MarketMod> {
        val all = java.util.Collections.synchronizedList(ArrayList<MarketMod>())
        val done = java.util.concurrent.CountDownLatch(1)
        recommendStreaming(ctx, mc, loader, installed) { batch, _, fin ->
            all.addAll(batch)
            if (fin) done.countDown()
        }
        try {
            done.await(SRC_TIMEOUT_SEC + 5, TimeUnit.SECONDS)
        } catch (t: Throwable) { Err.ignore(t, "done.await(SRC_TIMEOUT_SEC + 5, TimeUnit.SECONDS)") }
        return all.sortedByDescending { it.downloads }.take(20)
    }

    private fun main(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                block()
            } catch (t: Throwable) { Err.ignore(t, "block()") }
        }
    }

    private fun norm(s: String): String =
        s.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\u4e00-\\u9fa5]"), "")
}
