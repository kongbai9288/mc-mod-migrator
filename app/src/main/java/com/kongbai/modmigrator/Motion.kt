package com.kongbai.modmigrator

import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator

/**
 * 手势反馈与展开收起。
 *
 * 只做两件各页面都要用、但以前各写各的（或者根本没写）的事：
 * 1. 按下时的回弹——不写的话点下去没有任何反馈，看着像没点到。
 * 2. 折叠区的展开收起——不写的话高度瞬间跳变，内容"啪"地出现。
 *
 * 时长统一走 AnimPrefs，用户在设置里关掉动画后这里全部退化为瞬时完成。
 */
object Motion {

    private const val PRESS_SCALE = 0.96f

    /**
     * 给可点的视图挂上按下反馈。
     *
     * ⚠️ 只在点击**结束后**还原，不监听 ACTION_DOWN/UP：
     * 卡片内部还有子视图抢触摸事件，接管 onTouch 会让长按和滑动失效。
     */
    fun press(v: View) {
        v.setOnTouchListener(null)
        v.isClickable = true
        v.isFocusable = true
        v.setOnTouchListener { _, e ->
            when (e.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(PRESS_SCALE).scaleY(PRESS_SCALE)
                        .setDuration(80L).start()
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f)
                        .setDuration(AnimPrefs.dur(v.context, 120L)).start()
                }
            }
            false
        }
    }

    /**
     * 展开 / 收起。
     *
     * 做法是动画期间把高度从 0 量到内容实际高度，结束恢复 wrap_content——
     * 不恢复的话，内容后面再变高就会被永久截断。
     */
    fun expand(v: View, show: Boolean) {
        val ctx = v.context
        if (show) {
            v.visibility = View.VISIBLE
            if (!AnimPrefs.enabled(ctx)) {
                v.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                v.alpha = 1f
                return
            }
            v.measure(
                View.MeasureSpec.makeMeasureSpec(
                    (v.parent as? View)?.width ?: 0, View.MeasureSpec.EXACTLY
                ),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val target = v.measuredHeight
            v.layoutParams.height = 0
            v.alpha = 0f
            val a = ValueAnimator.ofInt(0, target)
            a.duration = AnimPrefs.dur(ctx, 180L)
            a.interpolator = DecelerateInterpolator()
            a.addUpdateListener {
                v.layoutParams.height = it.animatedValue as Int
                v.alpha = (it.animatedFraction)
                v.requestLayout()
            }
            a.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(p0: android.animation.Animator) {
                    v.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    v.alpha = 1f
                    v.requestLayout()
                }
            })
            a.start()
        } else {
            v.visibility = View.GONE
            v.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            v.alpha = 1f
        }
    }
}
