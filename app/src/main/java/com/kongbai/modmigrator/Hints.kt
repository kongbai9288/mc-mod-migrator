package com.kongbai.modmigrator

import android.view.View
import android.view.ViewGroup

/**
 * 说明文字按需展开。
 *
 * 之前每张卡片下面都常驻一段解释，一个设置页能堆出十几段，
 * 真正要操作的开关反而被埋在字里。这里统一处理：默认不显示，
 * 点标题右边的「?」才展开。
 *
 * 用法（纯 XML，不用改 Kotlin）：
 *   给说明 TextView 加  android:tag="hint"
 *   给触发的「?」加     android:tag="hintbtn"
 * 两者在同一个父容器里即可自动配对。一个容器允许多段说明一起展开。
 */
object Hints {
    private const val TAG_BTN = "hintbtn"
    private const val TAG_HINT = "hint"

    fun bind(root: View) {
        (root as? ViewGroup)?.let { walk(it) }
    }

    private fun walk(g: ViewGroup) {
        var btn: View? = null
        val hints = ArrayList<View>()
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            when (c.tag) {
                TAG_BTN -> btn = c
                TAG_HINT -> hints.add(c)
            }
        }
        if (btn != null && hints.isNotEmpty()) {
            hints.forEach { it.visibility = View.GONE }
            btn.safeClickUnit {
                val show = hints[0].visibility != View.VISIBLE
                hints.forEach { Motion.expand(it, show) }
            }
        }
        for (i in 0 until g.childCount) {
            (g.getChildAt(i) as? ViewGroup)?.let { walk(it) }
        }
    }
}
