package com.kongbai.modmigrator

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.util.concurrent.Executors

/**
 * MC 资讯：官方最新版本 / 最新模组 / 最新投影 / 红石。
 *
 * 四个分区各自独立抓取，任何一个挂了只影响它自己那一块，
 * 其余照常显示——不会出现"一个接口挂了整页空白"。
 */
class McFeedFragment : Fragment() {

    private val exec = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var box: LinearLayout
    private lateinit var refresh: SwipeRefreshLayout

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()

        refresh = SwipeRefreshLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val scroll = ScrollView(ctx)
        box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(box)
        refresh.addView(scroll)

        refresh.setOnRefreshListener {
            McFeed.clearCache()
            load()
        }
        refresh.isRefreshing = true
        load()
        return refresh
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun load() {
        val ctx = context ?: return
        val zones = McFeed.labels()
        // 一次加载的序号：下拉刷新会重新 load()，
        // 旧一轮的回调必须能被识别出来丢掉，否则会把上一轮的结果
        // 插进新一轮的骨架里（下拉刷新几次后列表会串）。
        val gen = ++generation
        // 四个分区**并发**跑，这里数一下完成了几个：
        // 之前的 `handler.post { isRefreshing = false }` 是**立刻**执行的，
        // 而真正的抓取在后台线程还没开始/没回来 →
        // 下拉刷新的转圈一闪就没了，内容却要过几秒才出来，
        // 用户以为刷新无效。改成等全部完成再停。
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        // 先画骨架，逐块填充，用户能看着一块块出来
        val slots = HashMap<String, LinearLayout>()
        handler.post {
            box.removeAllViews()
            for (z in zones) {
                box.addView(UiCards.sectionTitle(ctx, z.label))
                val slot = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                }
                box.addView(slot)
                slots[z.key] = slot
                slot.addView(UiCards.hint(ctx, "正在从 ${z.site} 获取…"))
            }
        }

        for (z in zones) {
            exec.execute {
                val list = try {
                    McFeed.fetch(ctx, z.key)
                } catch (t: Throwable) {
                    emptyList()
                }
                handler.post {
                    // 不是本轮的结果就丢掉
                    if (gen != generation) return@post
                    val slot = slots[z.key] ?: return@post
                    if (!isAdded) return@post
                    slot.removeAllViews()
                    if (list.isEmpty()) {
                        // 降级：抓取失败也给用户一个去原站的入口
                        slot.addView(
                            UiCards.emptyCard(
                                ctx, "暂时没抓到内容",
                                "可能是网络不通，或站点改版导致解析失效。可以直接打开原站查看。"
                            )
                        )
                        slot.addView(
                            UiCards.outlinedButton(ctx, "打开 ${z.site}") {
                                WebActivity.open(ctx, z.url, z.label)
                            }
                        )
                    } else {
                        for (it in list) {
                            slot.addView(
                                UiCards.infoCard(
                                    ctx, R.drawable.ic_open_in_new,
                                    it.title,
                                    "来源：${it.source}${if (it.extra.isNotBlank()) " · ${it.extra}" else ""}",
                                    "查看"
                                ) { WebActivity.open(ctx, it.url, it.title) }
                            )
                        }
                    }
                    if (done.incrementAndGet() >= zones.size) {
                        addFooter(ctx)
                        refresh.isRefreshing = false
                    }
                }
            }
        }
    }

    /**
     * 补版权声明。
     *
     * 之前靠 `key == REDSTONE` 判断"最后一块"——但四个分区是**并发**的，
     * REDSTONE 未必最后完成；而且每下拉刷新一次就又 addView 一次，
     * 刷几次底部就摞着好几条一样的声明。
     * 改成：先移除已有的，再追加在末尾。
     */
    private fun addFooter(ctx: android.content.Context) {
        if (footer != null) box.removeView(footer)
        footer = TextView(ctx).apply {
            text = "以上内容分别来自各站点，版权归原作者所有，本应用仅作索引与跳转。"
            textSize = 11f
            gravity = Gravity.START
            setPadding(0, dp(10), 0, dp(4))
        }
        box.addView(footer)
    }

    /** 加载轮次，用于丢弃过期回调 */
    private var generation = 0
    private var footer: TextView? = null
    override fun onDestroyView() {
        super.onDestroyView()
        // 清理 Handler：页面销毁后若还有未执行的 post，
        // 回调里访问已销毁的 View 会直接崩。
        runCatching { handler.removeCallbacksAndMessages(null) }
    }

}
