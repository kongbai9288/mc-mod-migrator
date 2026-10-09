package com.kongbai.modmigrator

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton

/**
 * 界面动画设置：开关 + 速率。
 *
 * ⚠️ 速率档位之前只放在「设置 → 实验室」里，界面动画这一页**根本没有**。
 * 用户想调快慢得先摸到实验室，而实验室那一项只显示"动画速率：正常"，
 * 点开能改、但改完看不到任何说明 —— 反馈原话是"应用动画速率也有问题"。
 * 现在速率直接放在本页，改完当场有示例动画能看到差别。
 *
 * 另外：本页的控件全是代码 new 出来的。
 * 代码创建的 TextView **不会**自动套用主题的正文色，
 * 深色模式下就是黑底黑字（等于"没有字"），
 * 所以下面每个 TextView 都显式取主题色。
 */
class SettingsAnimFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val tvState = TextView(ctx).apply {
            textSize = 13f
            setTextColor(textColorPrimary(ctx))
            setPadding(0, 0, 0, 12)
        }
        root.addView(tvState)

        // ── 动画开关 ──────────────────────────────────────────
        val rg = RadioGroup(ctx)
        val opts = listOf(
            AnimPrefs.FOLLOW to getString(R.string.anim_follow),
            AnimPrefs.ON to getString(R.string.anim_on),
            AnimPrefs.OFF to getString(R.string.anim_off)
        )
        for ((m, label) in opts) {
            rg.addView(RadioButton(ctx).apply {
                text = label
                id = View.generateViewId()
                tag = m
                setPadding(0, 8, 0, 8)
            })
        }
        val cur = AnimPrefs.mode(ctx)
        for (i in 0 until rg.childCount) {
            val rb = rg.getChildAt(i) as RadioButton
            if (rb.tag == cur) rg.check(rb.id)
        }
        root.addView(rg)

        // ── 速率：慢 / 正常 / 快 ──────────────────────────────
        root.addView(TextView(ctx).apply {
            text = "动画速率"
            textSize = 14f
            setTextColor(textColorPrimary(ctx))
            setPadding(0, 20, 0, 4)
        })
        val rgSpeed = RadioGroup(ctx).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val speeds = listOf(0 to "慢", 1 to "正常", 2 to "快")
        for ((s, label) in speeds) {
            rgSpeed.addView(RadioButton(ctx).apply {
                text = label
                id = View.generateViewId()
                tag = s
                setPadding(0, 8, 24, 8)
            })
        }
        val curSpeed = AnimPrefs.speed(ctx)
        for (i in 0 until rgSpeed.childCount) {
            val rb = rgSpeed.getChildAt(i) as RadioButton
            if (rb.tag == curSpeed) rgSpeed.check(rb.id)
        }
        root.addView(rgSpeed)

        val demo = TextView(ctx).apply {
            text = "示例：点下面的按钮看动画效果"
            textSize = 13f
            setTextColor(textColorPrimary(ctx))
            setPadding(0, 20, 0, 8)
        }
        root.addView(demo)

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
        }
        root.addView(box)

        root.addView(MaterialButton(ctx).apply {
            text = "播放一次示例动画"
            setOnClickListener { playDemo(ctx, box) }
        })

        // ── 开场动画（从启动器进来时播放） ────────────────────
        // 单独放一节：它和上面的界面动画是两回事——
        // 上面管的是列表/切换的动效，这一项管的是开场那 3 秒。
        // 有人嫌开场慢，所以默认开、但随时能关。
        root.addView(TextView(ctx).apply {
            text = "开场动画"
            textSize = 14f
            setTextColor(textColorPrimary(ctx))
            setPadding(0, 24, 0, 4)
        })
        val tvSplashDesc = TextView(ctx).apply {
            textSize = 12f
            setTextColor(textColorPrimary(ctx))
            setPadding(0, 0, 0, 8)
        }
        root.addView(tvSplashDesc)

        val swSplash = com.google.android.material.materialswitch.MaterialSwitch(ctx).apply {
            text = "从启动器进来时播放"
            isChecked = Prefs.get(ctx).getBoolean(K.SPLASH_ANIM, true)
        }
        root.addView(swSplash)

        root.addView(MaterialButton(ctx).apply {
            text = "预览开场动画"
            setOnClickListener {
                val h = LauncherBrand.Handoff(
                    LauncherBrand.byKey("zalith2"), "Zalith Launcher 2", "2.0.7"
                )
                LauncherSplash.play(requireActivity(), h)
            }
        })

        fun refreshSplash() {
            val h = runCatching { LauncherBrand.handoff(requireActivity()) }.getOrNull()
            tvSplashDesc.text = buildString {
                append("固定 3 秒，播放时点任意位置可跳过。\n")
                append("当前这次：")
                append(LauncherBrand.describe(h))
                if (h == null) append("\n（直接打开本应用时不会播放）")
            }
        }
        refreshSplash()

        swSplash.setOnCheckedChangeListener { _, on ->
            Prefs.get(ctx).edit().putBoolean(K.SPLASH_ANIM, on).apply()
            Tips.short(ctx, if (on) "开场动画已开启" else "开场动画已关闭，之后直接进主界面")
        }

        fun refresh() {
            tvState.text = AnimPrefs.describe(ctx)
        }
        refresh()

        rg.setOnCheckedChangeListener { _, id ->
            val rb = rg.findViewById<RadioButton>(id) ?: return@setOnCheckedChangeListener
            val m = rb.tag as? Int ?: return@setOnCheckedChangeListener
            AnimPrefs.setMode(ctx, m)
            refresh()
            Tips.short(ctx, if (AnimPrefs.enabled(ctx)) "动画已开启" else "动画已关闭")
        }

        rgSpeed.setOnCheckedChangeListener { _, id ->
            val rb = rgSpeed.findViewById<RadioButton>(id) ?: return@setOnCheckedChangeListener
            val s = rb.tag as? Int ?: return@setOnCheckedChangeListener
            AnimPrefs.setSpeed(ctx, s)
            refresh()
            // 动画关闭时改速率是没效果的，必须当场说清楚，
            // 否则用户改完看不到变化，只会以为又坏了
            Tips.short(ctx, if (AnimPrefs.enabled(ctx)) "速率：${AnimPrefs.speedLabel(ctx)}"
                else "速率已设为「${AnimPrefs.speedLabel(ctx)}」，但当前动画是关闭的，看不到效果")
        }

        return root
    }

    private fun playDemo(ctx: android.content.Context, box: LinearLayout) {
        box.removeAllViews()
        val cards = List(4) { i ->
            TextView(ctx).apply {
                text = "第 ${i + 1} 行"
                textSize = 14f
                setTextColor(textColorPrimary(ctx))
                setPadding(0, 10, 0, 10)
            }
        }
        cards.forEach { box.addView(it) }
        // 依次淡入，关闭动画时会直接全部显示
        AnimPrefs.stagger(ctx, cards)
    }
}
