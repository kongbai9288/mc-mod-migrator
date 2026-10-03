package com.kongbai.modmigrator

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * 自动换行的横向容器（FlowLayout 的极简版）。
 *
 * 为什么需要它：商店卡片上要画出「这个模组支持哪些加载器」的全部图标。
 * 之前用的是横向 LinearLayout，一个模组支持 5 个以上就放不下，
 * 只能砍成 4 个 + "+N" 完事 —— 用户看到的是"图标不全"。
 * 加载器图标只有 14dp，换到第二行完全没有阅读负担，
 * 而"+2"这种写法等于把信息藏起来了。
 *
 * 只用系统 API 自己量，不引第三方库。
 * 只实现必要的两个回调：onMeasure（算行数与总高）和 onLayout（摆位置）。
 */
class WrapRow @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : ViewGroup(ctx, attrs, defStyle) {

    /** 行间距 */
    private val gapV: Int = (3 * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var lineW = 0
        var lineH = 0
        var totalH = 0
        var maxLineW = 0

        fun breakLine() {
            totalH += lineH
            if (lineW > maxLineW) maxLineW = lineW
            lineW = 0
            lineH = 0
        }

        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            measureChildWithMargins(c, widthMeasureSpec, 0, heightMeasureSpec, 0)
            val lp = c.layoutParams as? MarginLayoutParams
            val w = c.measuredWidth + (lp?.marginStart ?: 0) + (lp?.marginEnd ?: 0)
            val h = c.measuredHeight + (lp?.topMargin ?: 0) + (lp?.bottomMargin ?: 0)
            // 放不下就换行（但一行至少要容下一个，避免死循环）
            if (lineW > 0 && lineW + w > maxW) {
                breakLine()
                totalH += gapV
            }
            lineW += w
            if (h > lineH) lineH = h
        }
        if (lineW > 0 || lineH > 0) breakLine()

        val wantW = resolveSize(
            maxLineW + paddingLeft + paddingRight, widthMeasureSpec
        )
        val wantH = resolveSize(
            totalH + paddingTop + paddingBottom, heightMeasureSpec
        )
        setMeasuredDimension(wantW, wantH)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, p: Int) {
        val left0 = paddingLeft
        val maxW = r - l - paddingLeft - paddingRight
        var x = left0
        var y = paddingTop
        var lineH = 0

        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            val lp = c.layoutParams as? MarginLayoutParams
            val ms = lp?.marginStart ?: 0
            val me = lp?.marginEnd ?: 0
            val mt = lp?.topMargin ?: 0
            val w = c.measuredWidth
            val h = c.measuredHeight
            if (x > left0 && x + ms + w > left0 + maxW) {
                x = left0
                y += lineH + gapV
                lineH = 0
            }
            val cx = x + ms
            c.layout(cx, y + mt, cx + w, y + mt + h)
            x = cx + w + me
            val hh = h + mt + (lp?.bottomMargin ?: 0)
            if (hh > lineH) lineH = hh
        }
    }

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams =
        MarginLayoutParams(context, attrs)

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(p: LayoutParams?): LayoutParams =
        MarginLayoutParams(p)

    override fun checkLayoutParams(p: LayoutParams?): Boolean =
        p is MarginLayoutParams
}
