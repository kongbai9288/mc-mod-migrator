package com.kongbai.modmigrator

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 周报。
 *
 * 两部分内容，都不依赖单一数据源，某一部分拿不到不影响另一部分：
 *  1. 应用动态：从仓库 release 列表拉最近的更新说明
 *  2. 本机动态：从日志中心统计最近做了多少迁移、下载、出错
 *
 * 之前那种"一个接口挂了整页空白"的写法在这里不存在——
 * 两块各自 try，失败就显示各自的降级文案。
 */
class WeeklyReportFragment : Fragment() {

    private val exec = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var box: LinearLayout
    private lateinit var tvState: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        // ⚠️ 提示里写着"下拉刷新"，之前却**根本没有 SwipeRefreshLayout** ——
        // 用户照着提示下拉，什么都不会发生。
        refresh = androidx.swiperefreshlayout.widget.SwipeRefreshLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val scroll = android.widget.ScrollView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(root)
        refresh.addView(scroll)

        root.addView(UiCards.hint(ctx, "最近发生了什么。下拉刷新或重进本页可重新拉取。"))

        tvState = TextView(ctx).apply {
            text = "正在生成周报…"
            textSize = 12f
            setTextColor(resources.getColor(R.color.textSecondary, null))
        }
        root.addView(tvState)

        box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(box)

        refresh.setOnRefreshListener { load() }
        refresh.isRefreshing = true
        load()
        return refresh
    }

    private fun load() {
        val ctx = context ?: return
        val gen = ++generation
        tvState.text = "正在生成周报…"
        box.removeAllViews()

        // ⚠️ 之前三个分区是**各自往 box 里 addView**，谁先跑完谁排在前面。
        // 「社区动态」要联网抓（慢），「应用动态」也要联网（慢），
        // 「本机动态」是本地计算（快）——于是每次进页面的分区顺序都不一样，
        // 而且还会互相插队：慢的那个回来时直接追加到末尾，
        // 可能把「本机动态」的卡片夹在「应用动态」中间。
        // 现在先建好三个空的占位槽，各自只往自己的槽里填，顺序就固定了。
        slotSites = newSlot()
        slotLocal = newSlot()
        slotRemote = newSlot()
        box.addView(slotSites!!)
        box.addView(slotLocal!!)
        box.addView(slotRemote!!)

        pending.set(2)

        // 本机动态：本地数据，几乎立刻出结果
        renderLocal()

        renderSites(gen)

        exec.execute {
            val remote = try {
                fetchReleases(ctx)
            } catch (t: Throwable) {
                emptyList<Pair<String, String>>()
            }
            safePost(handler) {
                if (gen != generation) return@safePost
                renderRemote(remote)
                if (pending.decrementAndGet() <= 0) finishLoad()
            }
        }
    }

    private fun newSlot(): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
    }

    private fun finishLoad() {
        tvState.text = "生成于 ${now()}"
        refresh.isRefreshing = false
    }

    /** 加载轮次，用于丢弃过期回调（下拉刷新时旧结果不能插进来） */
    private var generation = 0
    private var slotSites: LinearLayout? = null
    private var slotLocal: LinearLayout? = null
    private var slotRemote: LinearLayout? = null
    private lateinit var refresh: androidx.swiperefreshlayout.widget.SwipeRefreshLayout

    /** 本机动态：从日志统计 */
    private fun renderLocal() {
        val ctx = context ?: return
        val slot = slotLocal ?: return
        slot.addView(UiCards.sectionTitle(ctx, "本机动态"))
        try {
            val lines = LogCenter.all()
            val total = lines.size
            val errors = lines.count { it.level.equals("E", true) }
            val migrate = lines.count { it.msg.contains("迁移") }
            val download = lines.count { it.msg.contains("已安装") || it.msg.contains("下载") }

            val sb = StringBuilder()
            sb.append("日志条目：$total 条\n")
            sb.append("其中错误：$errors 条\n")
            sb.append("迁移相关：$migrate 次\n")
            sb.append("下载安装：$download 次\n")
            if (errors > 0) {
                sb.append("\n最近的错误：\n")
                lines.filter { it.level.equals("E", true) }
                    .takeLast(3)
                    .forEach { sb.append("· ${it.msg.take(80)}\n") }
            }
            slot.addView(card(ctx, "使用情况", sb.toString()))
        } catch (t: Throwable) {
            slot.addView(UiCards.emptyCard(ctx, "暂无本地数据", "用一阵子再来看就有了。"))
        }
    }

    /**
     * 站点动态：抓外部站点的内容摘要。
     *
     * 每条都标明来源站点，点进去是原文地址——不伪装成自己的内容。
     * 抓不到就显示降级文案，不会让整页空白。
     */
    private fun renderSites(gen: Int) {
        val ctx = context ?: return
        val slot = slotSites ?: return
        slot.addView(UiCards.sectionTitle(ctx, "社区动态（来自各站点）"))
        exec.execute {
            val list = try {
                SiteFeed.fetch()
            } catch (t: Throwable) {
                emptyList<SiteFeed.Entry>()
            }
            handler.post {
                if (gen != generation) return@post
                if (!isAdded) return@post
                if (list.isEmpty()) {
                    slot.addView(
                        UiCards.emptyCard(
                            ctx, "暂时抓不到社区动态",
                            "可能网络不通，或站点改版导致解析失效。不影响其他功能。"
                        )
                    )
                    if (pendingDone()) finishLoad()
                    return@post
                }
                for ((source, entries) in SiteFeed.groupBySource(list)) {
                    val first = entries.first()
                    val sb = StringBuilder()
                    for (e in entries) {
                        sb.append("· ${e.title}\n")
                    }
                    // 点整张卡片应该跳到**第一条内容的原文**，
                    // 而不是站点首页 —— 之前传的是 `first.sourceUrl`（首页），
                    // 用户点进去看到的是站点主页，等于没跳转，
                    // 还得自己在列表里找刚才那条。
                    val card = UiCards.infoCard(
                        ctx, R.drawable.ic_open_in_new,
                        source, sb.toString(), "查看原文"
                    ) {
                        WebActivity.open(ctx, first.url, first.title)
                    }
                    slot.addView(card)
                }
                slot.addView(TextView(ctx).apply {
                    text = "以上内容分别来自各站点，版权归原作者所有。点击可跳转原文。"
                    textSize = 11f
                    setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
                })
                if (pendingDone()) finishLoad()
            }
        }
    }

    /** 两个联网分区都完成了才收尾 */
    private fun pendingDone(): Boolean = pending.decrementAndGet() <= 0
    private val pending = java.util.concurrent.atomic.AtomicInteger(2)

    /** 应用动态：仓库 release */
    private fun renderRemote(list: List<Pair<String, String>>) {
        val ctx = context ?: return
        val slot = slotRemote ?: return
        slot.addView(UiCards.sectionTitle(ctx, "应用动态"))
        if (list.isEmpty()) {
            slot.addView(
                UiCards.emptyCard(
                    ctx, "暂时拉不到更新记录",
                    "可能网络不通或仓库没发布过 release，不影响使用。"
                )
            )
            return
        }
        for ((tag, body) in list.take(6)) {
            slot.addView(card(ctx, tag, body.take(300).ifBlank { "（无说明）" }))
        }
    }

    private fun card(ctx: android.content.Context, title: String, body: String): View {
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(
                    if (android.os.Build.VERSION.SDK_INT >= 23) ctx.getColor(R.color.cardBg)
                    else @Suppress("DEPRECATION") ctx.resources.getColor(R.color.cardBg)
                )
                cornerRadius = (12 * ctx.resources.displayMetrics.density)
                setStroke(1, android.graphics.Color.parseColor("#14000000"))
            }
            val pad = (14 * ctx.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (8 * ctx.resources.displayMetrics.density).toInt()
            layoutParams = lp
        }
        c.addView(TextView(ctx).apply {
            text = title
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        c.addView(TextView(ctx).apply {
            text = body
            textSize = 12f
            setTextColor(
                if (android.os.Build.VERSION.SDK_INT >= 23) ctx.getColor(R.color.textSecondary)
                else @Suppress("DEPRECATION") ctx.resources.getColor(R.color.textSecondary)
            )
            setPadding(0, (6 * ctx.resources.displayMetrics.density).toInt(), 0, 0)
            setLineSpacing(0f, 1.35f)
        })
        return c
    }

    /** 拉 release 列表 */
    private fun fetchReleases(ctx: android.content.Context): List<Pair<String, String>> {
        val o = Prefs.get(ctx).getString(K.OWNER, "").orEmpty()
            .ifBlank { UpdateChecker.defaultOwner() }
        val r = Prefs.get(ctx).getString(K.REPO, "").orEmpty()
            .ifBlank { UpdateChecker.defaultRepo() }
        val token = Prefs.get(ctx).getString(K.TOKEN, "") ?: ""
        val url = "https://api.github.com/repos/${Http.enc(o)}/${Http.enc(r)}/releases?per_page=6"
        // 短超时 + GitHub 官方推荐的 Accept 头。
        // 之前用默认超时（读取 120 秒），网络不通时整页要干等两分钟
        // 才显示"暂时拉不到更新记录"。
        val headers = mutableMapOf("Accept" to "application/vnd.github+json")
        if (token.isNotBlank()) headers["Authorization"] = "Bearer $token"
        val json = Http.get(url, headers, Http.SHORT)
        val arr = Json.arr(json) ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (e in arr) {
            val tag = Json.s(e, "tag_name")
            if (tag.isBlank()) continue
            out.add(Pair(tag, Json.s(e, "body")))
        }
        return out
    }

    private fun now(): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date())
    override fun onDestroyView() {
        super.onDestroyView()
        // 清理 Handler：页面销毁后若还有未执行的 post，
        // 回调里访问已销毁的 View 会直接崩。
        runCatching { handler.removeCallbacksAndMessages(null) }
    }

}
