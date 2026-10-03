package com.kongbai.modmigrator

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 设置 → 主题色。
 *
 * 两处改动都是冲着"切主题色有 bug、没图标没字"去的：
 *
 * 1. **每个配色前面加了色块**（之前只有一行纯文字）。
 *    11 个中文名摆在一起只能逐条读，"森绿""青碧""碧蓝"根本分不清谁是谁。
 *
 * 2. **所有文字显式取主题色**。
 *    这里整页都是代码 new 出来的控件，`TextView(ctx)` 不会套用
 *    主题的 textColorPrimary，深色下就是黑底黑字——看着像"字没了"。
 *
 * 3. 选中项用色块描边表示，不再靠往名字前面加个 "✓" ——
 *    那个 ✓ 是纯文本符号，某些字体下显示成方框或干脆不显示。
 */
class SettingsThemeFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        val pad = (16 * resources.displayMetrics.density).toInt()
        box.setPadding(pad, pad, pad, pad)
        scroll.addView(box)

        val cur = ThemePrefs.index(ctx)
        val d = resources.displayMetrics.density

        for ((i, t) in ThemePrefs.themes().withIndex()) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, (d * 8).toInt(), 0, (d * 8).toInt())
                // 整行可点，不用非得戳那个小按钮
                setOnClickListener { apply(ctx, i, t.name) }
            }

            // 色块预览：选中项加一圈描边
            val swatch = View(ctx).apply {
                val s = (d * 28).toInt()
                layoutParams = LinearLayout.LayoutParams(s, s).apply {
                    marginEnd = (d * 12).toInt()
                }
                background = GradientDrawable().apply {
                    setColor(t.seedColor)
                    shape = GradientDrawable.OVAL
                    if (i == cur) setStroke((d * 2.5).toInt(), textColorPrimary(ctx))
                }
            }
            row.addView(swatch)

            val tv = TextView(ctx).apply {
                text = t.name
                textSize = 15f
                setTextColor(textColorPrimary(ctx))
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            row.addView(tv)

            if (i == cur) {
                row.addView(TextView(ctx).apply {
                    text = getString(R.string.theme_current)
                    textSize = 12f
                    setTextColor(textColorSecondary(ctx))
                })
            }
            box.addView(row)
        }
        return scroll
    }

    /**
     * 应用配色。
     *
     * 之前先弹一个"已应用"对话框、点确定才 recreate ——
     * 多一次点击不说，对话框本身在重建过程中还容易被系统直接丢弃，
     * 结果就是"点了没反应"。现在直接重建，重建后用户当场看到新配色。
     */
    private fun apply(ctx: android.content.Context, i: Int, name: String) {
        val prev = ThemePrefs.index(ctx)
        // commit 而不是 apply：重建会立刻读这个值，
        // apply 是异步落盘的，极端情况下新 Activity 读到的还是旧配色
        ThemePrefs.save(ctx, i)
        if (prev == i) {
            android.widget.Toast.makeText(ctx, "已经是「$name」了",
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(name)
            .setMessage(R.string.theme_apply)
            .setPositiveButton(R.string.ok) { _, _ -> activity?.recreateSafely() }
            .setNegativeButton(R.string.cancel) { _, _ -> ThemePrefs.save(ctx, prev) }
            .show()
    }
}
