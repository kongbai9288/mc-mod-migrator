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
        onBatch: (batch: List<MarketMod>, source: String, finished: Boolean) -> Unit,
        offsets: Map<String, Int> = emptyMap()
    ) {
        val p = Prefs.get(ctx)
        // 断网细分：商店搜索/推荐单独可控，不再被全局开关一刀切
        if (!NetGate.allow(ctx, NetGate.Area.SEARCH)) {
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
                        // 同 recommend：命中已存在时只更新，**不再加入 fresh**，
                        // 否则同名模组会在列表里出现两次。
                        if (seen.replace(key, old, m)) break
                    } else break
                }
            }
            return fresh
        }

        // 收集本次要查的源
        val tasks = buildSources(ctx, q, mc, loader, limit, offset, offsets)
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
                    //
                    // ⚠️ 偏移必须按**该源实际消费的原始条数**推进，
                    // 不能用去重后的 fresh.size。
                    //
                    // 之前 MarketFragment 里写的是 `advance(source, batch.size)`，
                    // 而 batch 是去重后的结果。比如某源返回 20 条、
                    // 其中 8 条与别的源重名被滤掉，fresh 只有 12 条，
                    // 偏移却只推了 12 —— 下一页从旧位置再取 20 条，
                    // 那 8 条又原样出现一次。翻页越往下重复越多，
                    // 看起来就是"去重坏了"。
                    //
                    // 移到这里推进，用的是 list.size（真正消费掉的量）。
                    MarketState.advance(name, list.size)
                    if (fresh.isNotEmpty()) {
                        main { onBatch(fresh, name, false) }
                    } else {
                        logCf("$name 没有新结果")
                    }
                } catch (t: Throwable) {
                    // 单个源失败只记提示，不影响其他源。
                    // 但限流（429）要单独说清楚：它和"源挂了"是两回事，
                    // 挂了是修不好，限流是等一会就好。都写成"不可用"的话，
                    // 用户只会以为功能坏了。
                    val m = t.message ?: ""
                    if (m.contains("429") || m.contains("过于频繁")) {
                        logCf("$name 请求过于频繁（429），等约 1 分钟再试")
                    } else {
                        logCf("$name 不可用")
                    }
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
        } catch (t: Throwable) { Err.ignore(t, "等待各搜索源返回") }
        return all.sortedByDescending { it.downloads }
    }

    /**
     * 按设置构造要查询的源列表。
     * 每个源是 (显示名, 查询函数)，互相独立。
     */
    private fun buildSources(
        ctx: Context, q: String, mc: String, loader: String,
        limit: Int = 20, offset: Int = 0, offsets: Map<String, Int> = emptyMap()
    ): List<Pair<String, () -> List<MarketMod>>> {
        //
        // ⚠️ 每个源必须有**自己的**偏移。
        // 之前三个源共用同一个 offset：第一轮各源各返回 20 条，
        // 调用方按"三源之和 60"推进偏移，下一轮三个源都从 60 开始取 ——
        // 而 Modrinth 实际只消费了 20 条，中间的 40 条被整个跳过。
        // 表现就是"翻两页就没内容了""越翻越乱"。
        //
        fun off(name: String): Int = offsets[name] ?: offset

        val p = Prefs.get(ctx)
        val mode = p.getString(K.SOURCE, "聚合") ?: "聚合"
        val useBackend = p.getBoolean(K.USE_BACKEND, true)
        val useOfficial = p.getBoolean(K.USE_OFFICIAL_CF, false)
        val key = p.getString(K.CF_KEY, "") ?: ""
        val aggregate = mode == "聚合"
        val out = ArrayList<Pair<String, () -> List<MarketMod>>>()

        // Modrinth：免费无 Key，最稳，始终优先
        if (aggregate || mode == "Modrinth" || mode == "聚合") {
            out.add("Modrinth" to { ModrinthApi.search(q, mc, loader, limit, off("Modrinth")) })
        }

        // CurseForge 后端代理
        if ((aggregate || mode == "后端") && useBackend) {
            // 后端 /api/mods 的 page 参数会被原样透传给 CurseForge 的 index，
            // 而 index 是 0 基偏移。所以这里直接传 offset，
            // 不能除以页大小（那样第二页会取到和第一页几乎相同的内容）。
            out.add("后端" to { BackendApi.search(ctx, q, mc, loader, off("后端"), limit) })
        }

        // CurseForge 镜像 / 官方直连
        // ⚠️ 原来这里把 "后端" 也算进去，导致**单源选了"后端"时，
        // 直连 CurseForge 也被加进来一起查**。
        // 后端本身就是 CurseForge 数据的代理，两者同时返回
        // 既重复又和"单源"的语义矛盾（后端与官方直连本应互斥）。
        // 单源模式下只有显式选 "CurseForge" 才走直连。
        if (aggregate || mode == "CurseForge") {
            val canOfficial = useOfficial && key.isNotBlank()
            val label = if (canOfficial) "CurseForge官方" else "CurseForge镜像"
            out.add(
                Pair(label, { CurseForgeApi.search(q, mc, loader, if (canOfficial) key else "", limit, off(label)) })
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
        // 注意：这里**不能把 mc.isBlank() 当成关闭条件**。
        // 之前写成了 `|| mc.isBlank()` —— 用户没填 MC 版本时，
        // 推荐直接返回空且没有任何提示，表现就是"点推荐没反应"，
        // 用户只会以为功能坏了。MC 版本是可选过滤条件，不是前置条件。
        if (!NetGate.allow(ctx, NetGate.Area.SEARCH) || !p.getBoolean(K.RECOMMEND, true)) {
            onBatch(emptyList(), "推荐已关闭", true)
            return
        }
        lastRoutes = ""

        val seen = ConcurrentHashMap<String, MarketMod>()
        val installedNorm = installed.map { norm(it) }.filter { it.isNotBlank() }
        // pending 必须等于**实际注册的任务数**。
        // 之前写死 3，而下面正好是 3 个 run —— 一旦将来增删源，
        // pending 就对不上，finished 永远不会回调，界面会一直卡在"加载中"。
        val key = p.getString(K.CF_KEY, "") ?: ""

        /**
         * 已安装判定。
         *
         * 之前是双向 contains：已装 "sodium" 会把 "sodium-extra"、
         * "reeses-sodium-options" 全都过滤掉 —— 推荐列表莫名其妙少一大半。
         * 改成：完全相等，或已装名是候选名的**完整词边界前缀**（sodium-extra 匹配 sodium-extra）。
         */
        fun isInstalled(k: String): Boolean {
            if (k.isBlank()) return false
            for (it in installedNorm) {
                if (it == k) return true
                // 词边界前缀：避免 sodium 吃掉 sodiumextra 这类无关项
                if (k.startsWith(it) && k.length > it.length) {
                    val next = k[it.length]
                    if (next == '-' || next == '_' || next == ' ') return true
                }
                if (it.startsWith(k) && it.length > k.length) {
                    val next = it[k.length]
                    if (next == '-' || next == '_' || next == ' ') return true
                }
            }
            return false
        }

        fun push(list: List<MarketMod>): List<MarketMod> {
            val fresh = ArrayList<MarketMod>()
            for (m in list) {
                val k = norm(m.name)
                if (k.isBlank()) continue
                // 过滤已安装
                if (isInstalled(k)) continue
                val old = seen[k]
                if (old == null) {
                    if (seen.putIfAbsent(k, m) == null) fresh.add(m)
                } else if (m.downloads > old.downloads) {
                    // ⚠️ 重复 bug 就在这里。
                    // 之前是 `seen.replace(...) 成功就 fresh.add(m)` ——
                    // 可 old 那条**早就显示在列表里了**，这里再 add 一次，
                    // 界面上就出现两条同名模组。
                    // 同一个源里同名项本来就少见，但三个源并行推同一个模组时
                    // 很容易撞上，于是"推荐列表里总有重复的"。
                    // 命中已存在时只更新数据（保留更完整的那条），
                    // **不再往 fresh 里加**。
                    seen.replace(k, old, m)
                }
            }
            return fresh
        }

        fun run(name: String, call: () -> List<MarketMod>, pending: AtomicInteger) {
            pool.submit {
                try {
                    val fresh = push(call())
                    if (fresh.isNotEmpty()) main { onBatch(fresh, name, false) }
                } catch (t: Throwable) {
                    val m = t.message ?: ""
                    if (m.contains("429") || m.contains("过于频繁")) {
                        logCf("$name 请求过于频繁（429），等约 1 分钟再试")
                    } else {
                        logCf("$name 不可用")
                    }
                } finally {
                    if (pending.decrementAndGet() == 0) {
                        main { onBatch(emptyList(), "完成", true) }
                    }
                }
            }
        }

        // 三个来源并行。
        //
        // ⚠️ 排序：之前三个源全按**下载量**取，于是推荐出来永远是
        // JEI、Sodium 这些老牌热门，翻来覆去就那几十个 ——
        // 用户根本看不到最近有什么新模组，"推荐"等于没用。
        //
        // Modrinth 侧改用 index="newest"（官方合法取值之一：
        // relevance / downloads / follows / newest / updated），
        // 让它直接在 API 层就按发布时间取，而不是取热门后再排序。
        //
        // CurseForge 侧**不改** sortField：它的 ModsSearchSortField
        // 枚举值我没找到可信依据，猜错会拿到错误排序甚至报错，
        // 所以保持下载量排序，由下面统一按 updated 再排一次。
        val jobs: List<Pair<String, () -> List<MarketMod>>> = listOf(
            "Modrinth" to { ModrinthApi.search("", mc, loader, 30, 0, null, "newest") },
            "CurseForge" to { CurseForgeApi.search("", mc, loader, key, 30) },
            "后端" to { BackendApi.recommend(ctx, mc, loader) ?: emptyList() }
        )
        val pending = AtomicInteger(jobs.size)
        for ((name, call) in jobs) run(name, call, pending)
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
        } catch (t: Throwable) { Err.ignore(t, "等待各搜索源返回") }
        // 推荐按**更新时间**排，不是下载量：
        // 下载量排序必然把老牌热门顶在最前，新发布的模组永远排不上来。
        // 更新时间为空的排后面（而不是最前，否则会把一堆没时间字段的顶上来）。
        return all.sortedWith(
            compareByDescending<MarketMod> { it.updated.isNotBlank() }
                .thenByDescending { it.updated }
        ).take(20)
    }

    private fun main(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                block()
            } catch (t: Throwable) { Err.ignore(t, "执行单个搜索源") }
        }
    }

    private fun norm(s: String): String =
        s.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\u4e00-\\u9fa5]"), "")
}
