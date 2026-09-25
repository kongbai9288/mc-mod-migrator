package com.kongbai.modmigrator

import android.content.Context
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/**
 * 彩蛋：播放一小段属于本应用的旋律，放完自动换一个主题色。
 *
 * 用 ToneGenerator 合成，不需要任何音频资源文件，
 * 也不会因为缺资源而"点了没反应"。
 * 旋律是本应用自己的动机（C-E-G-E-C-G-E-C），跟 MC 无关。
 */
object EggPlayer {

    private val handler = Handler(Looper.getMainLooper())
    private var playing = false

    /** 音符：频率 Hz + 时长 ms */
    private val MELODY = listOf(
        523 to 200,   // C5
        659 to 200,   // E5
        784 to 200,   // G5
        659 to 200,   // E5
        523 to 250,   // C5
        784 to 250,   // G5
        659 to 300,   // E5
        523 to 400    // C5
    )

    /**
     * 播放并在结束后切换主题。
     * @param onThemeChanged 切到的主题下标会回调给调用方（用于刷新界面）
     */
    fun play(ctx: Context, onThemeChanged: (Int) -> Unit) {
        if (playing) return
        playing = true

        // 后台播，避免卡界面
        Thread {
            try {
                val tg = ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 70)
                for ((freq, dur) in MELODY) {
                    // ToneGenerator 用的是 Bellcore 音调编号，需要换算
                    tg.startTone(freqToTone(freq), dur)
                    Thread.sleep((dur + 40).toLong())
                }
                Thread.sleep(300)
                try {
                    tg.release()
                } catch (t: Throwable) { Err.ignore(t, "tg.release()") }
            } catch (t: Throwable) {
                // 播放失败也要把主题换了，不能卡在"点了没反应"
                     Err.ignore(t, "播放失败也要把主题换了，不能卡在\"点了没反应\"")
                 }

            // 换到下一个主题色，循环
            val cur = ThemePrefs.index(ctx)
            val next = (cur + 1) % ThemePrefs.themes().size
            ThemePrefs.save(ctx, next)

            handler.post {
                playing = false
                try {
                    android.widget.Toast.makeText(
                        ctx,
                        "🎵 主题已切换为「${ThemePrefs.themes()[next].name}」",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } catch (t: Throwable) { Err.ignore(t, ").show()") }
                onThemeChanged(next)
            }
        }.start()
    }

    /**
     * 频率转 ToneGenerator 的 tone type。
     * Bellcore 编号：以 0 为 16.35Hz，每加 1 升半音（倍率 2^(1/12)）。
     */
    private fun freqToTone(freq: Int): Int {
        val n = (12.0 * kotlin.math.ln(freq / 16.35) / kotlin.math.ln(2.0)).toInt()
        // ToneGenerator 的音调编号范围有限，超出会抛异常
        return n.coerceIn(1, 100)
    }

    /** 是否正在播放 */
    fun isPlaying(): Boolean = playing
}
