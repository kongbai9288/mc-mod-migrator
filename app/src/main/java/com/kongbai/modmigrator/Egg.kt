package com.kongbai.modmigrator

import android.content.Context
import android.graphics.Color
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
 * 旋律用 AudioTrack 按真实频率合成正弦波，不依赖任何音频文件，
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
                playMelody()
            } catch (t: Throwable) {
                // 播不出也要弹调色板，不能卡在"点了没反应"
                Err.ignore(t, "播不出也要弹调色板，不能卡在\"点了没反应\"")
            }
            handler.post {
                playing = false
                showPalette(ctx)
            }
        }.start()
    }

    /**
     * 播旋律。
     *
     * ⚠️ 之前用 `ToneGenerator.startTone(freqToTone(523), dur)`，
     * 并把 523Hz 换算成 **MIDI 音高编号 72** 传进去。
     * 但联网核对后可以确认：**ToneGenerator 只能播放它预设的几十种音调**
     * （TONE_DTMF_* / TONE_SUP_* / TONE_CDMA_*），
     * 参数不是频率也不是 MIDI 编号，传 72 **播出来的根本不是 C5**，
     * 而是落在 CDMA 区间里的某个系统提示音。
     * 也就是说"本应用自己的旋律"从来没被正确播出过——
     * 只是一串听起来毫无旋律感的系统提示音。
     *
     * 要播**指定频率**必须用 AudioTrack 自己算 PCM 正弦波。
     * 这里用 16bit / 单声道 / 44100Hz，每个音符单独生成，
     * 并加淡入淡出包络（不加的话每个音首尾都会"啪"一声爆音）。
     */
    fun playMelody() {
        val sr = 44100
        val minBuf = android.media.AudioTrack.getMinBufferSize(
            sr,
            android.media.AudioFormat.CHANNEL_OUT_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return
        val track = android.media.AudioTrack(
            android.media.AudioManager.STREAM_MUSIC,
            sr,
            android.media.AudioFormat.CHANNEL_OUT_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2,
            android.media.AudioTrack.MODE_STREAM
        )
        try {
            track.play()
            // 音量别拉满，突然一声大响很吓人
            val amp = (Short.MAX_VALUE * 0.35).toInt()
            for ((freq, durMs) in MELODY) {
                val n = sr * durMs / 1000
                val pcm = ShortArray(n)
                val fadeIn = (n * 0.05).toInt().coerceAtLeast(1)
                val fadeOut = (n * 0.15).toInt().coerceAtLeast(1)
                for (i in 0 until n) {
                    val env = when {
                        i < fadeIn -> i.toDouble() / fadeIn
                        i > n - fadeOut -> (n - i).toDouble() / fadeOut
                        else -> 1.0
                    }
                    val s = kotlin.math.sin(2.0 * Math.PI * freq * i / sr)
                    pcm[i] = (amp * env * s).toInt().toShort()
                }
                // MODE_STREAM 的 write 是阻塞的，大致写完就是播完
                track.write(pcm, 0, n)
                // 音符之间留一点间隙，不然会连成一片
                Thread.sleep(40)
            }
            Thread.sleep(200)
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
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
            // ⚠️ 之前是 52dp，手指点的时候太小、容易点偏到隔壁颜色。
            // 现在 64dp，并把最小触摸尺寸显式设上去。
            val size = (64 * ctx.resources.displayMetrics.density).toInt()
            val gap = (8 * ctx.resources.displayMetrics.density).toInt()
            themes.forEachIndexed { i, t ->
                val dark = ColorUtils.calculateLuminance(t.seedColor) < 0.4
                val swatch = TextView(ctx).apply {
                    text = if (i == cur) "✓" else ""
                    gravity = Gravity.CENTER
                    // ⚠️ 之前「✓」**固定用白色**：遇到浅色/白色系配色
                    // （比如米白、浅黄），白勾画在浅底上**根本看不见**，
                    // 看起来就像"没选中"。现在按底色明暗选对比色。
                    setTextColor(if (dark) Color.WHITE else Color.BLACK)
                    textSize = 22f
                    minWidth = size
                    minHeight = size
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(t.seedColor)
                        cornerRadius = (10 * ctx.resources.displayMetrics.density)
                        // ⚠️ 选中态的描边之前**固定用白色**：换到浅色系配色时，
                        // 白描边压在浅底上几乎看不见，看不出哪个是当前选中的。
                        // 现在改成按底色明暗取对比色，浅底用深色描边。
                        setStroke(
                            if (i == cur) (3 * ctx.resources.displayMetrics.density).toInt() else 1,
                            if (i == cur) {
                                if (dark) Color.WHITE else Color.BLACK
                            } else {
                                if (dark) 0x40FFFFFF.toInt() else 0x30000000
                            }
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

            // 彩蛋深处：连点标题 7 次解锁实验性功能
            var titleTaps = 0
            root.getChildAt(0).setOnClickListener {
                if (++titleTaps >= 7) {
                    titleTaps = 0
                    val on = !Prefs.get(ctx).getBoolean(K.EXPERIMENTAL_UPGRADE, false)
                    Prefs.get(ctx).edit().putBoolean(K.EXPERIMENTAL_UPGRADE, on).apply()
                    Toast.makeText(
                        ctx,
                        if (on) "🔓 已解锁实验性功能：设置 → 实验室" else "已隐藏实验性功能",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

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
                    } catch (t: Throwable) { Err.ignore(t, "(ctx as? android.app.Activity)?.recreate()") }
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
                            // 「✓」的颜色也要跟着底色走，否则浅色块上看不见
                            val dark = ColorUtils.calculateLuminance(themes[j].seedColor) < 0.4
                            v.setTextColor(if (dark) Color.WHITE else Color.BLACK)
                            val bg = v.background as? android.graphics.drawable.GradientDrawable
                            bg?.setStroke(
                                if (j == i) (3 * ctx.resources.displayMetrics.density).toInt() else 1,
                                if (j == i) {
                                    if (dark) Color.WHITE else Color.BLACK
                                } else {
                                    if (dark) 0x40FFFFFF.toInt() else 0x30000000
                                }
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

    // 这里原来有个 freqToTone()：把 523Hz 换算成 MIDI 编号再传给
    // ToneGenerator.startTone()。ToneGenerator 的参数不是频率也不是 MIDI 编号，
    // 它只认 TONE_DTMF_* / TONE_SUP_* / TONE_CDMA_* 这些预设常量，
    // 所以那个换算是错的，播出来的不是我们写的旋律。
    // 现在直接用 AudioTrack 按真实频率合成，这个函数已删除。
}
