package com.kongbai.modmigrator

/**
 * Mojang 接口专用限速。
 *
 * 为什么要单独一层：
 * 项目里的 [NetGate] 是**全局**限速，默认 120 次/分钟，用户还能在设置里调到 240。
 * 那个速率对 Modrinth / CurseForge 没问题，但对 Mojang 太激进 —— Mojang 的
 * 认证服务按 **IP** 计数，超了不是返回慢，是直接封 IP，而且没有自助解封入口，
 * 要等它自己过期（社区反馈通常是几十分钟到数小时）。
 *
 * 这一层专门给 [SkinApi] 用，比 NetGate 严得多，且**持久化** ——
 * 杀进程、重启手机、重装之前都不会把计数清零，否则"疯狂点 + 重启"就能绕过。
 *
 * 三层滑动窗口，配额按 **HTTP 请求数**计（不是按"查询次数"）：
 *   一次完整查询 = 名字→UUID + UUID→皮肤 = 2 个请求，
 *   直接输 UUID 查询 = 1 个请求。
 *
 * 另外接了 429 冷却：真收到 429 说明已经踩线了，这时候必须**退避**而不是重试，
 * 否则就是把临时限流坐实成封 IP。冷却时长按连续触发次数指数增长。
 */
object MojangRate {

    /** 一次"输名字查询"消耗的请求数，用于把配额换算成"还能查几次"给用户看 */
    const val PER_QUERY = 2

    // ---- 三层窗口 ----
    private const val WIN_BURST = 10_000L      // 10 秒
    private const val WIN_HOUR = 600_000L      // 10 分钟
    private const val WIN_DAY = 86_400_000L    // 24 小时

    private const val MAX_BURST = 6            // 10 秒内，约等于 3 次完整查询
    private const val MAX_HOUR = 40            // 10 分钟内，约等于 20 次
    private const val MAX_DAY = 200            // 24 小时内，约等于 100 次

    /** 两个请求之间至少隔这么久，挡住"连点" */
    private const val MIN_GAP = 1_500L

    // ---- 429 冷却 ----
    private const val COOL_BASE = 60_000L      // 第一次触发：1 分钟
    private const val COOL_MAX = 3_600_000L    // 上限 1 小时

    private const val P_STAMPS = "mojang_rate_stamps"
    private const val P_COOL_UNTIL = "mojang_cool_until"
    private const val P_COOL_STRIKE = "mojang_cool_strike"

    /**
     * 判定结果。
     * [ok] = false 时 [waitMs] 是还要等多久，[reason] 是可直接显示的话。
     */
    data class Verdict(
        val ok: Boolean,
        val waitMs: Long = 0,
        val reason: String = ""
    ) {
        val waitSec: Int get() = ((waitMs + 999) / 1000).toInt().coerceAtLeast(1)
    }

    // Prefs 拿不到时（App 未初始化等）退化到内存计数，至少限流仍然生效
    private val memStamps = mutableListOf<Long>()
    private var memCoolUntil = 0L
    private var memStrike = 0

    private fun sp(): android.content.SharedPreferences? =
        Prefs.appCtx()?.let { Prefs.get(it) }

    private fun loadStamps(): MutableList<Long> {
        val p = sp()
        if (p == null) return synchronized(this) { memStamps.toMutableList() }
        val raw = p.getString(P_STAMPS, "") ?: ""
        if (raw.isBlank()) return mutableListOf()
        return raw.split(",").mapNotNull { it.trim().toLongOrNull() }.toMutableList()
    }

    private fun saveStamps(list: List<Long>) {
        val now = System.currentTimeMillis()
        val kept = list.filter { now - it < WIN_DAY && it <= now + 5_000 }
        val p = sp()
        if (p == null) {
            synchronized(this) {
                memStamps.clear()
                memStamps.addAll(kept)
            }
            return
        }
        p.edit().putString(P_STAMPS, kept.joinToString(",")).apply()
    }

    /**
     * 现在能不能再发 [cost] 个请求。
     * 不阻塞 —— 需要等就如实告诉调用方等多久，由界面显示倒计时。
     *
     * [cost] 为什么要能大于 1：一次完整查询内部要连着发两个请求
     * （名字→UUID、UUID→皮肤），它们之间几乎零间隔。如果这里按
     * "下一个请求"去判，[MIN_GAP] 会把一次查询的第二个请求自己拦死，
     * 表现就是"永远查不出结果"。所以入口一次性预留整次查询的额度。
     *
     * 注意这里只**判断**不记账：真正发请求时才 [record]。这样缓存命中
     * （一次网络都不发）就不会平白消耗额度。
     */
    fun check(now: Long = System.currentTimeMillis(), cost: Int = 1): Verdict {
        // 1) 冷却最优先：真被 429 过，别的都免谈
        val cool = sp()?.getLong(P_COOL_UNTIL, 0L)
            ?: synchronized(this) { memCoolUntil }
        if (now < cool) {
            return Verdict(false, cool - now, "已被 Mojang 限流，正在冷却")
        }

        val stamps = loadStamps()

        // 2) 最小间隔，挡连点
        val last = stamps.maxOrNull() ?: 0L
        val gapWait = MIN_GAP - (now - last)
        if (gapWait > 0) return Verdict(false, gapWait, "点太快了")

        // 3) 三个窗口，从短到长
        windowWait(stamps, now, WIN_BURST, MAX_BURST, cost)?.let {
            return Verdict(false, it, "短时间请求太多")
        }
        windowWait(stamps, now, WIN_HOUR, MAX_HOUR, cost)?.let {
            return Verdict(false, it, "请求太频繁")
        }
        windowWait(stamps, now, WIN_DAY, MAX_DAY, cost)?.let {
            return Verdict(false, it, "今天已经查了很多次了")
        }

        return Verdict(true)
    }

    /**
     * 窗口内放不下 [cost] 个新请求的话，返回还要等多久；放得下返回 null。
     *
     * 算法：窗口内已有 n 个，要再塞 cost 个，就得让 n + cost <= max，
     * 也就是窗口里得先腾出 k = n - max + cost 个位置。等第 k 个（从旧往新数）
     * 移出窗口的那一刻才满足条件，于是返回它过期所需的时间。
     */
    private fun windowWait(
        stamps: List<Long>, now: Long, win: Long, max: Int, cost: Int
    ): Long? {
        val inWin = stamps.filter { now - it < win }.sorted()
        val k = inWin.size - max + cost
        if (k <= 0) return null
        val boundary = inWin[k - 1]
        return (win - (now - boundary)).coerceAtLeast(MIN_GAP)
    }

    /** 记一次成功的请求 */
    fun record(now: Long = System.currentTimeMillis()) {
        val list = loadStamps()
        list.add(now)
        saveStamps(list)
    }

    /**
     * 收到 429。进入冷却，连续触发则指数退避。
     *
     * 这里刻意**不做重试**：429 本身就是"你太频繁了"的答复，
     * 立刻重试只会把临时限流坐实成封 IP。
     */
    fun onTooMany(now: Long = System.currentTimeMillis()) {
        val p = sp()
        val strike = (p?.getInt(P_COOL_STRIKE, 0) ?: synchronized(this) { memStrike }) + 1
        //
        // 位移上限取 6 而不是 5：60000 << 5 = 32 分钟，够不到 COOL_MAX，
        // 那样 COOL_MAX 这个常量就成了摆设。取 6 才是 60 分钟。
        // 序列：60s → 120s → 240s → 480s → 960s → 1920s → 3600s（封顶）
        //
        val shift = (strike - 1).coerceAtMost(6)
        val dur = (COOL_BASE shl shift).coerceAtMost(COOL_MAX)
        if (p == null) {
            synchronized(this) {
                memStrike = strike
                memCoolUntil = now + dur
            }
            return
        }
        p.edit()
            .putInt(P_COOL_STRIKE, strike)
            .putLong(P_COOL_UNTIL, now + dur)
            .apply()
    }

    /** 请求成功，把连续触发计数清零（冷却时间不清，让它自然到期） */
    fun onSuccess() {
        val p = sp()
        if (p == null) synchronized(this) { memStrike = 0 }
        else p.edit().putInt(P_COOL_STRIKE, 0).apply()
    }

    /** 现在处于冷却中返回剩余毫秒，否则 0 */
    fun coolingMs(now: Long = System.currentTimeMillis()): Long {
        val cool = sp()?.getLong(P_COOL_UNTIL, 0L)
            ?: synchronized(this) { memCoolUntil }
        return (cool - now).coerceAtLeast(0)
    }

    /** 当天还剩多少次"完整查询"（按 2 个请求一次换算） */
    fun remainingQueries(now: Long = System.currentTimeMillis()): Int {
        val used = loadStamps().count { now - it < WIN_DAY }
        return ((MAX_DAY - used) / PER_QUERY).coerceAtLeast(0)
    }

    /** 清掉计数，仅供设置页"重置限速"或测试使用 */
    fun reset() {
        val p = sp()
        if (p == null) {
            synchronized(this) {
                memStamps.clear()
                memCoolUntil = 0L
                memStrike = 0
            }
            return
        }
        p.edit()
            .remove(P_STAMPS)
            .remove(P_COOL_UNTIL)
            .remove(P_COOL_STRIKE)
            .apply()
    }
}
