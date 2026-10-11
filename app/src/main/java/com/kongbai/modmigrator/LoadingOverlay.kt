package com.kongbai.modmigrator

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.atomic.AtomicInteger

/**
 * 统一的「加载中」遮罩。
 *
 * 为什么单独做一层：
 *   之前每个慢功能各写各的提示 —— 有的改一行 TextView、有的弹个对话框、
 *   有的什么都不显示直接卡住几秒。用户点开一个功能，界面纹丝不动，
 *   只能猜它是在忙还是坏了。
 *
 * 现在所有耗时入口都调这里，样式和措辞一致，也只有一个地方要改。
 *
 * 用法：
 *   val h = LoadingOverlay.show(this, "正在扫描…")
 *   // 后台干完
 *   LoadingOverlay.hide(this, h)
 *
 * 注意 show/hide 必须成对。页面销毁时调 [hideAll] 收尾，
 * 否则遮罩会跟着 Activity 一起泄漏（虽然不会崩，但会一直盖着）。
 */
object LoadingOverlay {

    private val seq = AtomicInteger(0)
    private val views = HashMap<Int, View>()

    /** 显示遮罩，返回一个句柄；hide 时要传回来 */
    fun show(act: AppCompatActivity, msg: String): Int {
        val id = seq.incrementAndGet()
        val host = act.findViewById<FrameLayout>(android.R.id.content) ?: return id

        val wrap = FrameLayout(act).apply {
            setBackgroundColor(0xAA000000.toInt())
            isClickable = true          // 吃掉点击，避免加载时误触后面的控件
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        box.addView(ProgressBar(act))
        box.addView(TextView(act).apply {
            text = msg
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setPadding(0, 20, 0, 0)
        })

        wrap.addView(
            box,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        // 已经有一个在显示就先顶掉，避免两层遮罩叠在一起越来越黑
        runCatching { host.addView(wrap) }
        views[id] = wrap
        return id
    }

    fun text(act: AppCompatActivity, id: Int, msg: String) {
        val v = views[id] ?: return
        runCatching {
            (v as FrameLayout).getChildAt(0)?.let { box ->
                if (box is LinearLayout) {
                    (box.getChildAt(1) as? TextView)?.text = msg
                }
            }
        }
    }

    fun hide(act: AppCompatActivity, id: Int) {
        val v = views.remove(id) ?: return
        runCatching {
            val host = act.findViewById<FrameLayout>(android.R.id.content)
            host?.removeView(v)
        }
    }

    /** 页面销毁时兜底：把手上所有遮罩都撤掉 */
    fun hideAll(act: AppCompatActivity) {
        val host = runCatching {
            act.findViewById<FrameLayout>(android.R.id.content)
        }.getOrNull()
        if (views.isEmpty()) return
        for ((_, v) in views) runCatching { host?.removeView(v) }
        views.clear()
    }
}
