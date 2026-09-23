package com.kongbai.modmigrator

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * 界面动画设置。
 *
 * 低端机默认关动画，避免滑动卡顿；用户可强制开或强制关。
 * 改完立刻生效，并给一个示例动画让用户看到效果。
 */
class SettingsAnimFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val tvState = TextView(ctx).apply {
            textSize = 13f
            setTextColor(resources.getColor(R.color.textSecondary, null))
            setPadding(0, 0, 0, 12)
        }
        root.addView(tvState)

        val rg = RadioGroup(ctx)
        val opts = listOf(
            AnimPrefs.FOLLOW to getString(R.string.anim_follow),
            AnimPrefs.ON to getString(R.string.anim_on),
            AnimPrefs.OFF to getString(R.string.anim_off)
        )
        for ((m, label) in opts) {
            rg.addView(RadioButton(ctx).apply {
                text = label
                id = View.generateViewId()
                tag = m
                setPadding(0, 8, 0, 8)
            })
        }
        // 选中当前模式
        val cur = AnimPrefs.mode(ctx)
        for (i in 0 until rg.childCount) {
            val rb = rg.getChildAt(i) as RadioButton
            if (rb.tag == cur) rg.check(rb.id)
        }
        root.addView(rg)

        val demo = TextView(ctx).apply {
            text = "示例：点下面的按钮看动画效果"
            textSize = 13f
            setPadding(0, 20, 0, 8)
        }
        root.addView(demo)

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
        }
        root.addView(box)

        val btnDemo = com.google.android.material.button.MaterialButton(ctx).apply {
            text = "播放一次示例动画"
            setOnClickListener {
                box.removeAllViews()
                val cards = List(4) { i ->
                    TextView(ctx).apply {
                        text = "第 ${i + 1} 行"
                        textSize = 14f
                        setPadding(0, 10, 0, 10)
                    }
                }
                cards.forEach { box.addView(it) }
                // 依次淡入，关闭动画时会直接全部显示
                AnimPrefs.stagger(ctx, cards)
            }
        }
        root.addView(btnDemo)

        fun refresh() {
            tvState.text = AnimPrefs.describe(ctx)
        }
        refresh()

        rg.setOnCheckedChangeListener { _, id ->
            val rb = rg.findViewById<RadioButton>(id) ?: return@setOnCheckedChangeListener
            val m = rb.tag as? Int ?: return@setOnCheckedChangeListener
            AnimPrefs.setMode(ctx, m)
            refresh()
            android.widget.Toast.makeText(
                ctx, if (AnimPrefs.enabled(ctx)) "动画已开启" else "动画已关闭",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        return root
    }
}
