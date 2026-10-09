package com.kongbai.modmigrator

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * 「更多」页：所有没放到底部导航栏的功能都在这里。
 *
 * 之前是一长条竖排大卡片，十六个入口要滚很久，找东西全靠记位置。
 * 现在按用途分组 + 方格网格，和工具箱保持一致，一屏能看全。
 *
 * 每一项都可以「移到底部导航栏」（长按）；在设置 → 导航栏自定义里可以反向移回来。
 * 视图全部用代码构建，不新增布局文件，避免 id 冲突。
 */
class MoreFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(box)

        val hint = TextView(ctx)
        hint.text = getString(R.string.more_hint)
        hint.textSize = 12f
        box.addView(hint)

        val pages = NavConfig.more(ctx)
        if (pages.isEmpty()) {
            box.addView(
                UiCards.emptyCard(
                    ctx, "所有功能都在底部栏了",
                    "到「设置 → 底部导航栏」可以把功能移回这里。"
                )
            )
            return scroll
        }

        for (g in GROUPS) {
            val list = pages.filter { groupOf(it.key) == g }
            if (list.isEmpty()) continue
            box.addView(UiCards.sectionTitle(ctx, g))
            box.addView(UiCards.grid(ctx, 3, list.map { p ->
                UiCards.tile(ctx, p.icon, getString(p.title)) { openPage(p) }
                    .apply { setOnLongClickListener { moveToBottom(p.key) } }
            }))
        }

        // 兜底：分组覆盖了全部已知 key，但万一以后加了新项忘了归类，
        // 不能让它凭空消失 —— 归到「其他」里照常显示。
        val others = pages.filter { p -> GROUPS.none { it == groupOf(p.key) } }
        if (others.isNotEmpty()) {
            box.addView(UiCards.sectionTitle(ctx, "其他"))
            box.addView(UiCards.grid(ctx, 3, others.map { p ->
                UiCards.tile(ctx, p.icon, getString(p.title)) { openPage(p) }
                    .apply { setOnLongClickListener { moveToBottom(p.key) } }
            }))
        }

        return scroll
    }

    private val GROUPS = listOf("常用", "商店与社区", "服务器与传输", "工具与文件", "设置与其他")

    private fun groupOf(key: String): String = when (key) {
        "migration", "mods", "update", "tools", "favorites" -> "常用"
        "market", "devs", "feed", "weekly" -> "商店与社区"
        "server", "sync", "sites" -> "服务器与传输"
        "plugin", "trash" -> "工具与文件"
        "settings", "log" -> "设置与其他"
        else -> "其他"
    }

    /** 长按卡片：把这一项移到底部导航栏 */
    private fun moveToBottom(key: String): Boolean {
        val ctx = context ?: return false
        val ok = NavConfig.moveToBottom(ctx, key)
        Tips.short(ctx, if (ok) "已移到底部导航栏" else "底部最多 4 个，先移一个出来")
        if (ok) {
            (activity as? MainActivity)?.rebuildNav()
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, MoreFragment())
                .commit()
        }
        return true
    }

    private fun openPage(p: NavConfig.Item) {
        try {
            val f = p.make()
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, f)
                .addToBackStack(null)
                .commit()
        } catch (t: Throwable) { Err.ignore(t, ".commit()") }
    }
}
