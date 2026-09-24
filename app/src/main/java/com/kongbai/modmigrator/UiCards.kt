package com.kongbai.modmigrator

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils

/**
 * 通用卡片组件。
 *
 * 之前各页面都是一堆裸 TextView 堆在一起，信息没层次、也没分隔，
 * 看起来就是一整片文字。这里提供几个共用的卡片样式，
 * 让开发者名单、工具箱、更多这类列表页有统一的视觉：
 *
 *   - devCard：带头像圆圈的名单项
 *   - infoCard：标题 + 说明 + 可选操作
 *   - sectionTitle：分组标题
 */
object UiCards {

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    /** 主题色（跟随用户选的主题） */
    private fun primary(ctx: Context): Int {
        val tv = TypedValue()
        return try {
            ctx.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, tv, true)
            if (tv.resourceId != 0) {
                if (android.os.Build.VERSION.SDK_INT >= 23) ctx.getColor(tv.resourceId)
                else @Suppress("DEPRECATION") ctx.resources.getColor(tv.resourceId)
            } else tv.data
        } catch (t: Throwable) {
            Color.parseColor("#2E7D32")
        }
    }

    /** 圆角白底卡片背景 */
    private fun cardBg(ctx: Context, radiusDp: Int = 12): GradientDrawable =
        GradientDrawable().apply {
            setColor(
                try {
                    if (android.os.Build.VERSION.SDK_INT >= 23) ctx.getColor(R.color.cardBg)
                    else @Suppress("DEPRECATION") ctx.resources.getColor(R.color.cardBg)
                } catch (t: Throwable) {
                    Color.WHITE
                }
            )
            cornerRadius = dp(ctx, radiusDp).toFloat()
            setStroke(1, Color.parseColor("#14000000"))
        }

    /** 圆形头像背景 */
    private fun avatarBg(ctx: Context, seed: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            // 用名称做种，给每个人一个稳定的颜色
            val base = primary(ctx)
            val hues = floatArrayOf(0f, 0.12f, -0.12f, 0.25f, -0.25f, 0.4f)
            val h = hues[Math.abs(seed) % hues.size]
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(base, hsl)
            hsl[0] = (hsl[0] + h * 360f + 360f) % 360f
            hsl[1] = (hsl[1] * 0.75f).coerceIn(0.25f, 0.85f)
            hsl[2] = 0.45f
            setColor(ColorUtils.HSLToColor(hsl))
        }

    /** 分组标题 */
    fun sectionTitle(ctx: Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(primary(ctx))
            val p = dp(ctx, 4)
            setPadding(dp(ctx, 2), dp(ctx, 16), p, dp(ctx, 6))
        }

    /**
     * 名单卡片：圆形首字母头像 + 名字 + 角色 + 可点击打开主页。
     */
    /**
     * 名单卡片：圆形头像（有头像地址就加载，否则用首字母色块）
     * + 名字 + 角色 + 右上角外链图标，整块可点。
     */
    fun devCard(
        ctx: Context,
        name: String,
        role: String,
        url: String = "",
        avatarUrl: String = "",
        onClick: (() -> Unit)? = null
    ): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardBg(ctx)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(ctx, 8)
            layoutParams = lp
        }

        // 首字母头像（先显示，网络图加载成功再盖上去）
        val initial = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val size = dp(ctx, 44)
        val avatarBox = android.widget.FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
        avatarBox.addView(TextView(ctx).apply {
            text = initial
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = avatarBg(ctx, name.hashCode())
            layoutParams = android.widget.FrameLayout.LayoutParams(size, size)
        })

        // 网络头像：Coil 异步加载，圆形裁切；失败就留着首字母
        if (avatarUrl.isNotBlank()) {
            val iv = ImageView(ctx).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(size, size)
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                // 圆角遮罩
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) {
                        o.setOval(0, 0, v.width, v.height)
                    }
                }
                clipToOutline = true
            }
            avatarBox.addView(iv)
            try {
                coil.ImageLoader(ctx).enqueue(
                    coil.request.ImageRequest.Builder(ctx)
                        .data(avatarUrl)
                        .target(iv)
                        .crossfade(true)
                        .listener(onError = { _, _ -> avatarBox.removeView(iv) })
                        .build()
                )
            } catch (t: Throwable) {
                avatarBox.removeView(iv)
            }
        }
        card.addView(avatarBox)

        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            lp.marginStart = dp(ctx, 12)
            layoutParams = lp
        }

        texts.addView(TextView(ctx).apply {
            text = name
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(
                if (android.os.Build.VERSION.SDK_INT >= 23) ctx.getColor(R.color.textPrimary)
                else @Suppress("DEPRECATION") ctx.resources.getColor(R.color.textPrimary)
            )
        })

        if (role.isNotBlank()) {
            texts.addView(TextView(ctx).apply {
                text = role
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(
                    if (android.os.Build.VERSION.SDK_INT >= 23) ctx.getColor(R.color.textSecondary)
                    else @Suppress("DEPRECATION") ctx.resources.getColor(R.color.textSecondary)
                )
                setPadding(0, dp(ctx, 2), 0, 0)
            })
        }
        card.addView(texts)

        if (url.isNotBlank()) {
            card.addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_open_in_new)
                val s2 = dp(ctx, 20)
                layoutParams = LinearLayout.LayoutParams(s2, s2)
                alpha = 0.5f
            })
            card.setOnClickListener { onClick?.invoke() }
            card.isClickable = true
        }
        return card
    }

    /**
     * 信息卡片：图标 + 标题 + 说明 + 可选按钮。
     * 工具箱、更多这类页面用它，视觉比裸按钮整齐。
     */
    fun infoCard(
        ctx: Context,
        icon: Int,
        title: String,
        desc: String,
        actionLabel: String = "",
        onClick: () -> Unit
    ): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardBg(ctx)
            setPadding(dp(ctx, 14), dp(ctx, 14), dp(ctx, 14), dp(ctx, 14))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(ctx, 10)
            layoutParams = lp
            setOnClickListener { onClick() }
            isClickable = true
        }

        card.addView(ImageView(ctx).apply {
            setImageResource(icon)
            val s = dp(ctx, 28)
            layoutParams = LinearLayout.LayoutParams(s, s)
            setColorFilter(primary(ctx))
        })

        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            lp.marginStart = dp(ctx, 12)
            layoutParams = lp
        }
        texts.addView(TextView(ctx).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        if (desc.isNotBlank()) {
            texts.addView(TextView(ctx).apply {
                text = desc
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(
                    if (android.os.Build.VERSION.SDK_INT >= 23) ctx.getColor(R.color.textSecondary)
                    else @Suppress("DEPRECATION") ctx.resources.getColor(R.color.textSecondary)
                )
                setPadding(0, dp(ctx, 3), 0, 0)
            })
        }
        card.addView(texts)

        if (actionLabel.isNotBlank()) {
            card.addView(TextView(ctx).apply {
                text = actionLabel
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(primary(ctx))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
        }
        return card
    }

    /** 空状态卡片：没有数据时给个说明，而不是一片空白 */
    fun emptyCard(ctx: Context, title: String, desc: String): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = cardBg(ctx)
            setPadding(dp(ctx, 20), dp(ctx, 32), dp(ctx, 20), dp(ctx, 32))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(ctx, 8)
            layoutParams = lp
            addView(TextView(ctx).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
            })
            addView(TextView(ctx).apply {
                text = desc
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(
                    if (android.os.Build.VERSION.SDK_INT >= 23) ctx.getColor(R.color.textSecondary)
                    else @Suppress("DEPRECATION") ctx.resources.getColor(R.color.textSecondary)
                )
                gravity = Gravity.CENTER
                setPadding(0, dp(ctx, 8), 0, 0)
            })
        }

    /** 一行说明文字（带内边距，用于页面顶部提示） */
    fun hint(ctx: Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(
                if (android.os.Build.VERSION.SDK_INT >= 23) ctx.getColor(R.color.textSecondary)
                else @Suppress("DEPRECATION") ctx.resources.getColor(R.color.textSecondary)
            )
            setPadding(dp(ctx, 2), dp(ctx, 4), dp(ctx, 2), dp(ctx, 10))
            setLineSpacing(0f, 1.3f)
        }
}
