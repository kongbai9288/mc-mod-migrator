package com.kongbai.modmigrator

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton

/**
 * 底部导航栏自定义。
 *
 * 可以：
 *   - 把「更多」里的功能移到底部栏（正向移动）
 *   - 把底部栏的功能移回「更多」（反向移动）
 *   - 在底部栏内上下调序
 *
 * Material 的 BottomNavigationView 硬限制 5 项，这里始终保证不超过。
 */
class SettingsNavFragment : Fragment() {

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private lateinit var boxBottom: LinearLayout
    private lateinit var boxMore: LinearLayout
    private lateinit var tvTip: TextView

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

        tvTip = TextView(ctx).apply {
            textSize = 12f
            setTextColor(resources.getColor(R.color.textSecondary, null))
        }
        root.addView(tvTip)

        root.addView(TextView(ctx).apply {
            text = "底部导航栏（长按可上下调序）"
            textSize = 14f
            setPadding(0, 12, 0, 6)
        })
        boxBottom = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(boxBottom)

        root.addView(TextView(ctx).apply {
            text = "更多（不在底部栏的）"
            textSize = 14f
            setPadding(0, 16, 0, 6)
        })
        boxMore = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(boxMore)

        refresh()
        return scroll
    }

    private fun toast(s: String) {
        handler.post {
            if (!isAdded) return@post
            try {
                context?.let { android.widget.Toast.makeText(it, s, android.widget.Toast.LENGTH_SHORT).show() }
            } catch (t: Throwable) {
                // 界面已销毁，不弹
                     Err.ignore(t, "界面已销毁，不弹")
                 }
        }
    }

    private fun refresh() {
        val ctx = context ?: return
        val bottom = NavConfig.bottom(ctx)
        val more = NavConfig.more(ctx)
        // ⚠️ 之前这里写死"底部最多 5 项"，而 `NavConfig.MAX_CUSTOM` 实际是 **4**
        // （因为主界面还会固定加一个「更多」，加起来才是 Material 的 5 项上限）。
        // 两处数字对不上：用户看到底部只放了 4 个就被拒绝，
        // 提示却说"最多 5 个"，会以为还能再加一个，反复点都是失败。
        // 改成直接取常量，不再写死。
        tvTip.text =
            "底部最多 ${NavConfig.MAX_CUSTOM} 个自定义项（另有一个固定的「更多」，"
                .plus("合计不超过 Material 的 5 项上限）。")
                .plus("\n当前底部 ${bottom.size} 个、更多 ${more.size} 个。")

        boxBottom.removeAllViews()
        for ((i, key) in bottom.withIndex()) {
            val nav = NavConfig.item(key) ?: continue
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 4, 0, 4)
            }
            val label = TextView(ctx).apply {
                text = getString(nav.title)
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(label)
            val up = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            up.text = "↑"
            up.setOnClickListener {
                if (!NavConfig.moveInBottom(ctx, key, true)) toast("已经在最上面")
                refresh()
            }
            row.addView(up)
            val down = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            down.text = "↓"
            down.setOnClickListener {
                if (!NavConfig.moveInBottom(ctx, key, false)) toast("已经在最下面")
                refresh()
            }
            row.addView(down)
            val out = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            out.text = "移出"
            out.setOnClickListener {
                if (!NavConfig.moveToMore(ctx, key)) toast("底部至少保留 1 项")
                else {
                    toast("已移到「更多」")
                    (activity as? MainActivity)?.rebuildNav()
                }
                refresh()
            }
            row.addView(out)
            boxBottom.addView(row)
        }

        boxMore.removeAllViews()
        // 变量名不能用 it：setOnClickListener 的 lambda 隐式参数也叫 it，会遮蔽外层
        for (page in more) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 4, 0, 4)
            }
            val label = TextView(ctx).apply {
                text = getString(page.title)
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(label)
            val add = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            add.text = "移到底部"
            add.setOnClickListener {
                if (!NavConfig.moveToBottom(ctx, page.key)) {
                    toast("底部自定义项已满（${NavConfig.MAX_CUSTOM} 个），先从上面移一个出来")
                } else {
                    toast("已移到底部导航栏")
                    (activity as? MainActivity)?.rebuildNav()
                }
                refresh()
            }
            row.addView(add)
            boxMore.addView(row)
        }
    }
    override fun onDestroyView() {
        super.onDestroyView()
        // 清理 Handler：页面销毁后若还有未执行的 post，
        // 回调里访问已销毁的 View 会直接崩。
        runCatching { handler.removeCallbacksAndMessages(null) }
    }

}
