package com.kongbai.modmigrator

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 配置区三态折叠。
 *
 * 市场页和服务器页都是「上面一块筛选 / 连接参数，下面一个结果列表」。
 * 之前两部分按 1:2 写死分屏：上面永远只给三分之一，参数一多就得在
 * 那一小块里来回滚，看着像"显示不全"；而结果列表被挤成两行，
 * 又像"搜不到东西"。两头都难受。
 *
 * 三态：
 *  · 收起 —— 配置整块隐藏，整屏给列表。有结果时默认进这一档
 *  · 分屏 —— 配置与列表各一半
 *  · 配置优先 —— 配置占满，列表暂时隐藏。填参数时用它
 *
 * 三档循环，按钮文字直接写出「点一下会变成什么」，
 * 不写「展开 / 收起」这种要猜当前是哪种状态的词。
 */
object CfgToggle {

    const val COLLAPSED = 0
    const val SPLIT = 1
    const val FULL = 2

    /**
     * @param cfg 配置区容器（NestedScrollView 之类）
     * @param list 结果列表
     * @param btn 触发按钮
     * @param label 配置区叫什么，用于拼按钮文案
     */
    fun render(
        cfg: View?,
        list: View?,
        btn: TextView?,
        state: Int,
        label: String = "配置"
    ) {
        val lp = cfg?.layoutParams as? LinearLayout.LayoutParams
        when (state) {
            COLLAPSED -> {
                cfg?.visibility = View.GONE
                list?.visibility = View.VISIBLE
                setWeight(list, 1f)
                btn?.text = "展开$label"
            }
            SPLIT -> {
                cfg?.visibility = View.VISIBLE
                list?.visibility = View.VISIBLE
                lp?.weight = 1f
                cfg?.layoutParams = lp
                setWeight(list, 1f)
                btn?.text = "${label}占满"
            }
            else -> {
                cfg?.visibility = View.VISIBLE
                list?.visibility = View.GONE
                lp?.weight = 1f
                cfg?.layoutParams = lp
                btn?.text = "看结果"
            }
        }
    }

    fun next(state: Int): Int = (state + 1) % 3

    private fun setWeight(v: View?, w: Float) {
        val lp = v?.layoutParams as? LinearLayout.LayoutParams ?: return
        lp.weight = w
        v.layoutParams = lp
    }

    /** 列表下面那些配套控件（标题、日志）跟着列表一起显隐，否则全屏配置时会孤零零留在底下 */
    fun bind(vararg extra: View?): List<View?> = extra.toList()
}
