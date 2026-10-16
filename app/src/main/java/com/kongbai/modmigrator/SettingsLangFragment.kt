package com.kongbai.modmigrator

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import java.util.Locale

/**
 * 设置 → 语言。
 *
 * 两条路：
 *  1. 内置英文（values-en），点一下直接切。
 *  2. 语言拓展包：工作目录 lang/ 下的 .json，格式 {"字符串名":"译文"}，
 *     优先级高于内置语言，缺的键自动退回默认文案。
 *
 * Android 13 起应用内改语言不再生效，必须走系统设置——
 * 但老版本直接跳走一句提示都没有，用户点了「简体中文」界面纹丝不动，
 * 只会以为按钮坏了。跳之前会先说明。
 */
class SettingsLangFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        return ComposeView(ctx).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent { McTheme(ctx) { LangScreen(ctx) } }
        }
    }
}

@Composable
private fun LangScreen(ctx: android.content.Context) {
    var gen by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun current(): String {
        val code = Prefs.get(ctx).getString(K.LANG_CODE, "") ?: ""
        return "当前：" + when (code) {
            "en" -> "English"
            "zh" -> "简体中文"
            else -> ctx.getString(R.string.lang_system) + "（${Locale.getDefault().displayLanguage}）"
        } + if (LangPack.loadedName.isNotBlank()) "\n已加载拓展包：${LangPack.loadedName}" else ""
    }

    fun setLocale(code: String) {
        Prefs.get(ctx).edit().putString(K.LANG_CODE, code).apply()
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                Tips.long(ctx, "Android 13 起应用不能自己改语言，请在打开的页面里把系统语言设为需要的语言。")
                ctx.startActivity(Intent(Settings.ACTION_LOCALE_SETTINGS))
            } else {
                val l = if (code.isBlank()) Locale.getDefault() else Locale(code)
                Locale.setDefault(l)
                val cfg = ctx.resources.configuration
                // Android 7.0 起资源解析走 LocaleList，单改 setLocale 只影响第一个条目，
                // 部分机型会"切了英文但还有中文"。
                if (Build.VERSION.SDK_INT >= 24) cfg.setLocales(android.os.LocaleList(l))
                else @Suppress("DEPRECATION") cfg.setLocale(l)
                @Suppress("DEPRECATION")
                ctx.resources.updateConfiguration(cfg, ctx.resources.displayMetrics)
                (ctx as? android.app.Activity)?.recreateSafely()
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        McNote(remember(gen) { current() })

        McChipRow(
            listOf(ctx.getString(R.string.lang_system), "English", "简体中文"),
            remember(gen) {
                when (Prefs.get(ctx).getString(K.LANG_CODE, "") ?: "") {
                    "en" -> 1
                    "zh" -> 2
                    else -> 0
                }
            },
            { setLocale(listOf("", "en", "zh")[it]) }
        )

        McSectionTitle("语言拓展包")
        McNote(ctx.getString(R.string.lang_pack_hint))

        McButtonRow(
            ctx.getString(R.string.lang_pack_scan),
            {
                if (busy) return@McButtonRow
                busy = true
                Tips.short(ctx, "正在扫描…")
                // LangPack.load 走 SAF 遍历 lang/ 并逐个读 JSON —— 磁盘 IO
                Bg.serial.execute {
                    val n = LangPack.load(ctx)
                    Bg.ui.post {
                        busy = false
                        gen++
                        dialog = if (n <= 0) ctx.getString(R.string.lang_pack_none)
                        else ctx.getString(R.string.lang_pack_found, n.toString(), LangPack.loadedName)
                    }
                }
            },
            "导出语言包模板",
            {
                if (busy) return@McButtonRow
                busy = true
                Bg.serial.execute {
                    val ok = runCatching { LangPack.writeTemplate(ctx) }.getOrDefault(false)
                    Bg.ui.post {
                        busy = false
                        dialog = if (ok) "已生成 lang/template.json，改完重命名即可生效。需要重新点「扫描语言拓展包」加载。"
                        else "生成失败：工作目录不可用，请先设置工作目录。"
                    }
                }
            }
        )

        McFilledButton("打开工作目录 lang 文件夹", !busy) {
            val u = WorkDir.uri(ctx)
            if (u.isNotBlank()) runCatching { WebActivity.open(ctx, u, "") }
        }
    }

    dialog?.let { msg ->
        McConfirm("语言拓展包", msg, ctx.getString(R.string.ok), {
            dialog = null
            (ctx as? android.app.Activity)?.recreateSafely()
        }, { dialog = null })
    }
}
