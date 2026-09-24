package com.kongbai.modmigrator

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.ActivityManager
import android.content.Context
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator

/**
 * 界面动画开关。
 *
 * 设计要点：
 * 1. 默认跟着设备性能走——低端机（系统判定为低内存设备，或 RAM 较小）
 *    默认关闭动画，避免卡顿；好机器默认开。
 * 2. 用户可以在设置里手动覆盖（开 / 关 / 跟随设备）。
 * 3. 所有动画调用都走这里，关闭时直接把 View 设成最终状态，
 *    不会出现「关了动画但界面停在半透明」这类问题。
 */
object AnimPrefs {

    /** 模式：0=跟随设备 1=总是开 2=总是关 */
    const val FOLLOW = 0
    const val ON = 1
    const val OFF = 2

    fun mode(ctx: Context): Int = Prefs.get(ctx).getInt(K.ANIM_MODE, FOLLOW)

    fun setMode(ctx: Context, m: Int) {
        Prefs.get(ctx).edit().putInt(K.ANIM_MODE, m).apply()
    }

    /** 这台设备算不算低端：系统标记低内存，或可用内存偏小 */
    fun isLowEnd(ctx: Context): Boolean {
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null && am.isLowRamDevice) return true
            val mi = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            // 总内存小于 4GB 视为低端
            if (mi.totalMem > 0 && mi.totalMem < 4L * 1024 * 1024 * 1024) return true
        } catch (t: Throwable) {
        }
        return false
    }

    /** 当前是否应该播放动画 */
    fun enabled(ctx: Context): Boolean {
        return when (mode(ctx)) {
            ON -> true
            OFF -> false
            else -> !isLowEnd(ctx)
        }
    }

    /**
     * 显示时长的基准值；关闭动画时为 0。
     *
     * 速率可调（设置 → 实验室 → 动画速率）：
     *   慢 = 1.6 倍时长（看得清）、正常 = 1 倍、快 = 0.5 倍（干脆利落）。
     */
    fun dur(ctx: Context, ms: Long = 220L): Long {
        if (!enabled(ctx)) return 0L
        val f = when (speed(ctx)) {
            0 -> 1.6f   // 慢
            2 -> 0.5f   // 快
            else -> 1.0f
        }
        return (ms * f).toLong()
    }

    /** 动画速率：0=慢 1=正常 2=快 */
    fun speed(ctx: Context): Int = Prefs.get(ctx).getInt(K.ANIM_SPEED, 1).coerceIn(0, 2)

    fun setSpeed(ctx: Context, sp: Int) {
        Prefs.get(ctx).edit().putInt(K.ANIM_SPEED, sp.coerceIn(0, 2)).apply()
    }

    fun speedLabel(ctx: Context): String = when (speed(ctx)) {
        0 -> "慢"
        2 -> "快"
        else -> "正常"
    }

    /**
     * 淡入。关闭动画时直接设为可见。
     */
    fun fadeIn(ctx: Context, v: View, delayMs: Long = 0L) {
        if (!enabled(ctx)) {
            v.alpha = 1f
            v.visibility = View.VISIBLE
            v.translationY = 0f
            return
        }
        v.visibility = View.VISIBLE
        v.alpha = 0f
        v.translationY = 12f
        v.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(dur(ctx))
            .setStartDelay(delayMs)
            .setInterpolator(DecelerateInterpolator())
            .setListener(null)
            .start()
    }

    /** 淡出 */
    fun fadeOut(ctx: Context, v: View, gone: Boolean = true) {
        if (!enabled(ctx)) {
            v.visibility = if (gone) View.GONE else View.INVISIBLE
            return
        }
        v.animate()
            .alpha(0f)
            .setDuration(dur(ctx, 160L))
            .setInterpolator(AccelerateDecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    v.visibility = if (gone) View.GONE else View.INVISIBLE
                }
            })
            .start()
    }

    /** 内容切换：先淡出旧的，再淡入新的 */
    fun swap(ctx: Context, outView: View, inView: View) {
        if (!enabled(ctx)) {
            outView.visibility = View.GONE
            inView.alpha = 1f
            inView.visibility = View.VISIBLE
            return
        }
        fadeOut(ctx, outView)
        fadeIn(ctx, inView, 120L)
    }

    /** 列表项依次入场；关闭动画时直接全部显示 */
    fun stagger(ctx: Context, views: List<View>) {
        if (!enabled(ctx)) {
            for (v in views) {
                v.alpha = 1f
                v.visibility = View.VISIBLE
            }
            return
        }
        views.forEachIndexed { i, v ->
            fadeIn(ctx, v, (i * 40L).coerceAtMost(400L))
        }
    }

    /** 进度条平滑变化（避免一格一格跳） */
    fun smoothProgress(ctx: Context, bar: android.widget.ProgressBar, to: Int) {
        if (!enabled(ctx)) {
            bar.progress = to
            return
        }
        val anim = android.animation.ObjectAnimator.ofInt(bar, "progress", to)
        anim.duration = dur(ctx, 300L)
        anim.interpolator = DecelerateInterpolator()
        anim.start()
    }

    /** 给用户看的描述，设置页展示用 */
    fun describe(ctx: Context): String {
        val low = isLowEnd(ctx)
        val cur = enabled(ctx)
        val m = mode(ctx)
        val modeTxt = when (m) {
            ON -> "总是开启"
            OFF -> "总是关闭"
            else -> "跟随设备（当前${if (low) "判定为低端机 → 关闭" else "判定为正常机 → 开启"}）"
        }
        return "动画：${if (cur) "已开启" else "已关闭"}\n模式：$modeTxt"
    }
}
