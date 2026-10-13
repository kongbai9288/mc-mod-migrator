package com.kongbai.modmigrator

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 全屏弹窗。
 *
 * 日志、插件说明、执行结果这类内容是长文本，普通对话框
 * 只给一小块区域，正文得在里面滚，一行长一点就被截断。
 * 这里直接铺满整屏，正文占满剩余高度，按钮在底部右对齐。
 *
 * 颜色取当前主题的属性，不写死，深色模式跟着变。
 */
object SheetDialog {

    /**
     * @param actions 按钮，从右往左排。回调为 null 时只关窗口。
     */
    fun show(
        ctx: Context,
        title: String,
        body: CharSequence,
        actions: List<Pair<String, (() -> Unit)?>> = emptyList()
    ) {
        val d = Dialog(ctx)
        val pad = (16 * ctx.resources.displayMetrics.density).toInt()
        val bg = color(ctx, android.R.attr.colorBackground, Color.WHITE)
        val fg = color(ctx, android.R.attr.textColorPrimary, Color.BLACK)
        val fg2 = color(ctx, android.R.attr.textColorSecondary, Color.GRAY)
        val accent = color(ctx, android.R.attr.colorAccent, fg)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(ctx).apply {
            text = title
            textSize = 18f
            setTextColor(fg)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, pad / 2)
        })

        val scroll = ScrollView(ctx)
        scroll.addView(TextView(ctx).apply {
            text = body
            textSize = 13f
            setTextColor(fg2)
            setLineSpacing(0f, 1.35f)
            setTextIsSelectable(true)
        })
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        if (actions.isNotEmpty()) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, pad / 2, 0, 0)
            }
            for ((label, act) in actions) {
                row.addView(Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
                    text = label
                    setTextColor(accent)
                    setOnClickListener {
                        act?.invoke()
                        d.dismiss()
                    }
                })
            }
            root.addView(row)
        }

        d.setContentView(root)
        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        d.show()
    }

    private fun color(ctx: Context, attr: Int, fallback: Int): Int {
        val tv = android.util.TypedValue()
        return if (ctx.theme.resolveAttribute(attr, tv, true)) tv.data else fallback
    }
}
