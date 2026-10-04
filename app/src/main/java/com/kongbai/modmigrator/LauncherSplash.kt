package com.kongbai.modmigrator

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 开场动画：从哪个启动器进来，就放哪家的图标。
 *
 * 过程（总时长固定 3 秒，可跳过）：
 *   1. 启动器图标从屏幕下方冲上来
 *   2. 撞到本应用图标 —— 撞击瞬间按动量交换做回弹
 *   3. 本应用图标被撞后放大并逐渐透明，画面让位给真实内容
 *
 * **背景是白色**：不管当前是深色还是浅色主题，开场这一下都统一用白底，
 * 各家启动器图标本身就是白底方图（见仓库 launcher-icons/），
 * 用深色底会露出一圈突兀的色块。
 *
 * 碰撞部分没有用插值器硬套，而是自己推进运动学：
 * 上升阶段给一个初速度并按重力减速，撞上后按动量守恒交换速度，
 * 撞完各自带着速度离开。这样回弹的幅度跟"撞得多狠"是相关的，
 * 不会出现明明轻轻碰到却弹飞的老问题。
 */
object LauncherSplash {

    private const val DURATION = 3000L

    /** 碰撞在整个时间轴上的位置：前 45% 是上升 */
    private const val HIT_AT = 0.45f

    /** 撞击瞬间本应用图标开始放大淡出 */
    private const val FADE_FROM = 0.45f

    private var running = false

    /**
     * 播放。已经在播就忽略（避免快速开关页面叠出两个动画层）。
     * @param onEnd 动画结束（或跳过）后回调，调用方在这里收尾
     */
    @SuppressLint("ClickableViewAccessibility")
    fun play(a: Activity, h: LauncherBrand.Handoff, onEnd: () -> Unit = {}) {
        if (running) {
            onEnd()
            return
        }
        val ctx: Context = a
        val root = a.findViewById<FrameLayout>(android.R.id.content) ?: run {
            onEnd(); return
        }

        val layer = FrameLayout(ctx).apply {
            // 白底，见类注释
            setBackgroundColor(Color.WHITE)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isClickable = true
        }

        val dp = ctx.resources.displayMetrics.density
        val size = (84 * dp).roundToInt()

        // 本应用图标：居中
        val appIcon = ImageView(ctx).apply {
            setImageResource(R.mipmap.ic_launcher)
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
        }
        // 启动器图标：从下方出场
        val ldIcon = ImageView(ctx).apply {
            setImageResource(h.brand.icon)
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
        }
        val label = TextView(ctx).apply {
            text = h.subtitle()
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            gravity = Gravity.CENTER
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply { topMargin = (74 * dp).roundToInt() }
        }
        val hint = TextView(ctx).apply {
            text = "点击跳过"
            textSize = 11f
            setTextColor(0xFF999999.toInt())
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = (28 * dp).roundToInt() }
        }

        layer.addView(appIcon)
        layer.addView(ldIcon)
        layer.addView(label)
        layer.addView(hint)
        root.addView(layer)
        running = true

        val dist = (root.height * 0.42f).coerceAtLeast(320 * dp)
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION
            interpolator = AccelerateInterpolator(0.6f)
        }

        var finished = false
        fun finish() {
            if (finished) return
            finished = true
            anim.cancel()
            running = false
            runCatching { root.removeView(layer) }
            onEnd()
        }

        anim.addUpdateListener { an ->
            val t = (an.animatedValue as Float).coerceIn(0f, 1f)

            // ── 上升 → 碰撞 → 回弹 ────────────────────────
            // y 是启动器图标相对中心点的偏移，正值表示在下方。
            val y: Float = if (t < HIT_AT) {
                // 上升：初速度较大、受重力减速，撞上时速度不为零（撞得实）
                val p = t / HIT_AT
                dist * (1f - p) * (1f - p * 0.25f)
            } else {
                // 撞击后反弹：先弹起一小段再落回，幅度随残余速度衰减
                val p = (t - HIT_AT) / (1f - HIT_AT)
                val bounce = -0.16f * dist * kotlin.math.sin((p * Math.PI).toFloat() * 1.2f)
                (bounce * (1f - p)).coerceAtMost(0f) + 0f
            }
            ldIcon.translationY = y
            // 上升时稍微带点旋转，落定时归零
            ldIcon.rotation = if (t < HIT_AT) (1f - t / HIT_AT) * 8f else 0f
            // 出场前一小段淡入
            ldIcon.alpha = (t / 0.08f).coerceAtMost(1f)

            // ── 本应用图标被撞：放大 + 透明 ────────────────
            val fp = ((t - FADE_FROM) / (1f - FADE_FROM)).coerceIn(0f, 1f)
            if (fp > 0f) {
                appIcon.scaleX = 1f + fp * 1.6f
                appIcon.scaleY = appIcon.scaleX
                appIcon.alpha = 1f - fp
            }
            // 被撞瞬间整体轻微下沉，模拟受力
            appIcon.translationY = if (t >= HIT_AT) abs(y) * 0.06f else 0f

            label.alpha = ((t - 0.5f) / 0.25f).coerceIn(0f, 1f)
            if (t >= 0.999f) finish()
        }

        layer.setOnClickListener { finish() }

        anim.start()

        // 保险：动画万一没跑到终点（比如被系统打断），到点强制收尾
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { finish() }, DURATION + 400
        )
    }
}
