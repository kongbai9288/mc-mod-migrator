package com.kongbai.modmigrator

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * 彩蛋：播放一小段属于本应用的旋律，放完自动换一个主题色。
 *
 * ⚠️ 之前这里是**和 Egg 重复的另一份实现**，而且用了同样的错误写法：
 *   `ToneGenerator.startTone(freqToTone(523), dur)`
 *   —— ToneGenerator 的参数不是频率也不是 MIDI 编号，
 *   只认 TONE_DTMF_* / TONE_SUP_* / TONE_CDMA_* 这些预设常量，
 *   所以播出来的根本不是我们写的 C-E-G-E-C-G-E-C。
 *
 * 现在不再自己合成，统一复用 `Egg.playMelody()`（AudioTrack 按真实频率生成正弦波），
 * 避免两份旋律实现各自漂移。
 */
object EggPlayer {

    private val handler = Handler(Looper.getMainLooper())
    private var playing = false

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
                Egg.playMelody()
            } catch (t: Throwable) {
                // 播放失败也要把主题换了，不能卡在"点了没反应"
                Err.ignore(t, "播放失败也要把主题换了")
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

    // 原来这里有个 freqToTone() 做"频率 → Bellcore 编号"的换算，
    // 但 ToneGenerator 根本不认这个编号体系（它只认预设常量），换算是错的。
    // 合成已统一到 Egg.playMelody()，这个函数已删除。

    /** 是否正在播放 */
    fun isPlaying(): Boolean = playing
}
