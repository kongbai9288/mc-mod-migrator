package com.kongbai.modmigrator

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.compose.runtime.LaunchedEffect

/**
 * 界面动画设置：开关 + 速率 + 开场动画。
 *
 * 速率档位之前只放在「设置 → 实验室」里，本页根本没有。
 * 用户想调快慢得先摸到实验室，而实验室那一项只显示"动画速率：正常"，
 * 点开能改、但改完看不到任何说明。现在速率直接放在本页，
 * 改完下面的示例能当场看出差别。
 *
 * 开场动画单独放一节：它和界面动画是两回事——
 * 上面管列表/切换的动效，这一项管开场那 3 秒。
 */
class SettingsAnimFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        return ComposeView(ctx).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent { McTheme(ctx) { AnimScreen(ctx) } }
        }
    }
}

@Composable
private fun AnimScreen(ctx: android.content.Context) {
    var mode by remember { mutableIntStateOf(AnimPrefs.mode(ctx)) }
    var speed by remember { mutableIntStateOf(AnimPrefs.speed(ctx)) }
    var splash by remember { mutableStateOf(Prefs.get(ctx).getBoolean(K.SPLASH_ANIM, true)) }
    var demoKey by remember { mutableIntStateOf(0) }

    val splashDesc = remember {
        val h = runCatching { LauncherBrand.handoff(ctx as? android.app.Activity) }.getOrNull()
        buildString {
            append("固定 3 秒，播放时点任意位置可跳过。\n")
            append("当前这次：").append(LauncherBrand.describe(h))
            if (h == null) append("\n（直接打开本应用时不会播放）")
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        McNote(AnimPrefs.describe(ctx))

        McSectionTitle("界面动画")
        McChipRow(
            listOf(ctx.getString(R.string.anim_follow), ctx.getString(R.string.anim_on), ctx.getString(R.string.anim_off)),
            mode
        ) {
            if (it == mode) return@McChipRow
            mode = it
            AnimPrefs.setMode(ctx, it)
            Tips.short(ctx, if (AnimPrefs.enabled(ctx)) "动画已开启" else "动画已关闭")
        }

        McSectionTitle("动画速率")
        McChipRow(listOf("慢", "正常", "快"), speed) {
            if (it == speed) return@McChipRow
            speed = it
            AnimPrefs.setSpeed(ctx, it)
            // 动画关闭时改速率是没效果的，必须当场说清楚，
            // 否则改完看不到变化，只会以为又坏了
            Tips.short(
                ctx,
                if (AnimPrefs.enabled(ctx)) "速率：${AnimPrefs.speedLabel(ctx)}"
                else "速率已设为「${AnimPrefs.speedLabel(ctx)}」，但当前动画是关闭的，看不到效果"
            )
        }

        McSectionTitle("示例")
        DemoRows(ctx, demoKey)
        McFilledButton("播放一次示例动画") { demoKey++ }

        McSectionTitle("开场动画")
        McNote(splashDesc)
        McCheckRow(splash, {
            splash = it
            Prefs.get(ctx).edit().putBoolean(K.SPLASH_ANIM, it).apply()
            Tips.short(ctx, if (it) "开场动画已开启" else "开场动画已关闭，之后直接进主界面")
        }) {
            Text("从启动器进来时播放", fontSize = 13.sp)
        }
        McFilledButton("预览开场动画") {
            val a = ctx as? android.app.Activity ?: return@McFilledButton
            val h = LauncherBrand.Handoff(
                LauncherBrand.byKey("zalith2"), "Zalith Launcher 2", "2.0.7"
            )
            LauncherSplash.play(a, h)
        }
    }
}

/** 四行依次淡入；动画关闭时直接全部显示 */
@Composable
private fun DemoRows(ctx: android.content.Context, key: Int) {
    val on = AnimPrefs.enabled(ctx)
    val step = AnimPrefs.dur(ctx, 120L)
    Column(Modifier.fillMaxWidth()) {
        for (i in 0 until 4) {
            val a = remember(key) { Animatable(if (on) 0f else 1f) }
            LaunchedEffect(key) {
                if (!on) { a.snapTo(1f); return@LaunchedEffect }
                a.snapTo(0f)
                a.animateTo(1f, tween(durationMillis = step.toInt(), delayMillis = (i * step).toInt()))
            }
            Text(
                "第 ${i + 1} 行",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(vertical = 6.dp)
                    .graphicsLayer { alpha = a.value }
            )
        }
    }
}
