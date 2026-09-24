package com.kongbai.modmigrator

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

/**
 * 开发者名单 + 致谢。
 *
 * 之前的版本在加载失败时 catch 里什么都不做，
 * 结果列表一片空白、连提示文字都停在「正在读取名单…」。
 * 现在无论读写成功与否都会渲染：远端拿不到就用内置名单，
 * 真出错了也显示错误卡片 + 重试入口，绝不留白。
 */
class DevsFragment : Fragment() {

    private val exec = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private var eggClicks = 0
    private lateinit var box: LinearLayout
    private lateinit var tvState: TextView

    /**
     * 彩蛋：播放一小段属于本应用的旋律，放完自动换主题色。
     * 用 ToneGenerator 合成，不依赖任何音频文件——
     * 之前那种依赖资源的做法在资源缺失时就会"点了没反应"。
     */
    /** 彩蛋：长按或连点触发，播旋律后弹调色板 */
    private fun showEgg() {
        val ctx = context ?: return
        Egg.show(ctx)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val scroll = android.widget.ScrollView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(root)

        root.addView(UiCards.hint(ctx, "感谢这些项目与个人。点击卡片可打开主页。\n（长按这里或连点 5 次有彩蛋）"))

        tvState = TextView(ctx).apply {
            text = "正在读取名单…"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(resources.getColor(R.color.textSecondary, null))
            setPadding(0, 8, 0, 8)
        }
        root.addView(tvState)

        box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(box)

        // 彩蛋：长按状态行或连点 5 次
        tvState.setOnLongClickListener { showEgg(); true }
        eggClicks = 0
        tvState.setOnClickListener {
            eggClicks++
            if (eggClicks >= 5) {
                eggClicks = 0
                showEgg()
            }
        }

        load()
        return scroll
    }

    private fun load() {
        val ctx = context ?: return
        tvState.text = "正在读取名单…"
        exec.execute {
            // load 内部已保证不抛异常，这里再兜一层
            val list = try {
                DevTeam.load(ctx)
            } catch (t: Throwable) {
                emptyList()
            }
            val err = list.isEmpty()
            safePost(handler) {
                box.removeAllViews()
                if (err) {
                    // 兜底：直接用内置名单，绝不留白
                    val fallback = DevTeam.builtin()
                    if (fallback.isNotEmpty()) {
                        render(fallback, "使用内置名单（${fallback.size} 项）")
                    } else {
                        box.addView(
                            UiCards.emptyCard(
                                ctx, "名单暂时读不出来",
                                "不影响使用，稍后重试即可。"
                            )
                        )
                        tvState.text = ""
                    }
                } else {
                    render(list, "共 ${list.size} 位 · 点击卡片打开主页")
                }
            }
        }
    }

    private fun render(list: List<DevTeam.Dev>, stateText: String) {
        val ctx = context ?: return
        box.removeAllViews()
        // 人（有主页链接的）和数据源分开显示
        val people = list.filter { it.url.contains("github.com") }
        val others = list.filter { !it.url.contains("github.com") }

        if (people.isNotEmpty()) {
            box.addView(UiCards.sectionTitle(ctx, "开发"))
            for (d in people) box.addView(devRow(ctx, d))
        }
        if (others.isNotEmpty()) {
            box.addView(UiCards.sectionTitle(ctx, "数据来源与协议"))
            for (d in others) box.addView(devRow(ctx, d))
        }
        if (people.isEmpty() && others.isEmpty()) {
            for (d in list) box.addView(devRow(ctx, d))
        }
        tvState.text = stateText
    }

    private fun devRow(ctx: android.content.Context, d: DevTeam.Dev): View {
        // 头像：名单里写了就用写的，没写就按地址推导（GitHub 用户头像 / 站点 favicon）
        val av = d.avatar.ifBlank { DevTeam.avatarOf(d.url, d.name) }
        return UiCards.devCard(ctx, d.name, d.role, d.url, av) {
            if (d.url.isNotBlank()) {
                WebActivity.open(ctx, d.url, d.name)
            }
        }
    }
}
