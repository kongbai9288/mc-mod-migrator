package com.kongbai.modmigrator

import android.content.Context
import android.graphics.Color
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 彩蛋。
 *
 * 触发：开发者名单页长按（或连点 5 次）。
 * 内容：先播一小段本应用自己的旋律，放完弹出调色板——
 *   11 套配色 + 深色/浅色切换，选完实时应用。
 *
 * 旋律用 ToneGenerator 合成，不依赖任何音频文件，
 * 不会因为缺资源而"点了没反应"。
 */
object Egg {

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var playing = false

    /** 本应用自己的动机：C-E-G-E-C-G-E-C */
    private val MELODY = listOf(
        523 to 180, 659 to 180, 784 to 180, 659 to 180,
        523 to 240, 784 to 240, 659 to 280, 523 to 380
    )

    fun show(ctx: Context) {
        if (playing) return
        playing = true
        Thread {
            try {
                val tg = ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 70)
                for ((freq, dur) in MELODY) {
                    tg.startTone(freqToTone(freq), dur)
                    Thread.sleep((dur + 40).toLong())
                }
                Thread.sleep(250)
                runCatching { tg.release() }
            } catch (t: Throwable) {
                // 播不出也要弹调色板，不能卡在"点了没反应"
            }
            handler.post {
                playing = false
                showPalette(ctx)
            }
        }.start()
    }

    /** 直接弹调色板（不播旋律，设置里也能进） */
    fun showPalette(ctx: Context) {
        try {
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val p = (18 * ctx.resources.displayMetrics.density).toInt()
                setPadding(p, p, p, p)
            }

            root.addView(TextView(ctx).apply {
                text = "🎨 换个颜色"
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, (10 * ctx.resources.displayMetrics.density).toInt())
            })

            // 配色网格
            val grid = GridLayout(ctx).apply {
                columnCount = 4
            }
            val themes = ThemePrefs.themes()
            val cur = ThemePrefs.index(ctx)
            val size = (52 * ctx.resources.displayMetrics.density).toInt()
            val gap = (8 * ctx.resources.displayMetrics.density).toInt()
            themes.forEachIndexed { i, t ->
                val swatch = TextView(ctx).apply {
                    text = if (i == cur) "✓" else ""
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    textSize = 18f
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(t.seedColor)
                        cornerRadius = (10 * ctx.resources.displayMetrics.density)
                        val dark = ColorUtils.calculateLuminance(t.seedColor) < 0.4
                        setStroke(
                            if (i == cur) (3 * ctx.resources.displayMetrics.density).toInt() else 1,
                            if (i == cur) Color.WHITE else
                                if (dark) 0x40FFFFFF else 0x30000000
                        )
                    }
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = size
                        height = size
                        setMargins(gap, gap, gap, gap)
                    }
                    setOnClickListener { }
                }
                grid.addView(swatch)
            }
            root.addView(grid)

            // 名字
            val tvName = TextView(ctx).apply {
                text = themes[cur].name
                gravity = Gravity.CENTER
                textSize = 13f
                setPadding(0, (8 * ctx.resources.displayMetrics.density).toInt(), 0, 0)
            }
            root.addView(tvName)

            // 深色模式
            val nightOpts = arrayOf("跟随系统", "浅色", "深色")
            val tvNight = TextView(ctx).apply {
                text = "深色模式：${ThemePrefs.nightLabel(ctx)}"
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, (12 * ctx.resources.displayMetrics.density).toInt(), 0, 0)
            }
            root.addView(tvNight)

            var picked = cur
            var pickedNight = ThemePrefs.nightMode(ctx)

            val dlg = MaterialAlertDialogBuilder(ctx)
                .setView(root)
                .setNeutralButton("深色模式") { _, _ -> }
                .setPositiveButton("应用") { _, _ ->
                    ThemePrefs.save(ctx, picked)
                    ThemePrefs.setNightMode(ctx, pickedNight)
                    Toast.makeText(
                        ctx, "已换成「${themes[picked].name}」", Toast.LENGTH_SHORT
                    ).show()
                    try {
                        (ctx as? android.app.Activity)?.recreate()
                    } catch (t: Throwable) {
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .create()

            dlg.setOnShowListener {
                // 网格点击：换选中
                for (i in 0 until grid.childCount) {
                    grid.getChildAt(i).setOnClickListener {
                        picked = i
                        for (j in 0 until grid.childCount) {
                            val v = grid.getChildAt(j) as TextView
                            v.text = if (j == i) "✓" else ""
                            val bg = v.background as? android.graphics.drawable.GradientDrawable
                            val dark = ColorUtils.calculateLuminance(themes[j].seedColor) < 0.4
                            bg?.setStroke(
                                if (j == i) (3 * ctx.resources.displayMetrics.density).toInt() else 1,
                                if (j == i) Color.WHITE else
                                    if (dark) 0x40FFFFFF else 0x30000000
                            )
                        }
                        tvName.text = themes[i].name
                    }
                }
                // 中性键改成切深色模式
                dlg.getButton(android.content.DialogInterface.BUTTON_NEUTRAL)
                    ?.setOnClickListener {
                        pickedNight = (pickedNight + 1) % 3
                        tvNight.text = "深色模式：${nightOpts[pickedNight]}"
                    }
            }
            dlg.show()
        } catch (t: Throwable) {
            Toast.makeText(ctx, "调色板打开失败：${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 频率转 ToneGenerator 的 tone type（Bellcore 编号） */
    private fun freqToTone(freq: Int): Int {
        val n = (12.0 * kotlin.math.ln(freq / 16.35) / kotlin.math.ln(2.0)).toInt()
        return n.coerceIn(1, 100)
    }
}
