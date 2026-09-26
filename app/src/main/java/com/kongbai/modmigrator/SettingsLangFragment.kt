package com.kongbai.modmigrator

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

/**
 * 设置 → 语言。
 *
 * 两条路：
 *  1. 内置英文（values-en），点一下直接切系统语言。
 *  2. 语言拓展包：工作目录 lang/ 下的 .json，格式 {"字符串名":"译文"}，
 *     优先级高于内置语言，缺的键自动退回默认文案。
 */
class SettingsLangFragment : Fragment() {

    private val exec = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        val pad = (16 * resources.displayMetrics.density).toInt()
        box.setPadding(pad, pad, pad, pad)
        scroll.addView(box)

        val tv = TextView(ctx)
        tv.textSize = 13f
        box.addView(tv)
        refresh(tv)

        box.addView(button(getString(R.string.lang_system)) {
            setLocale("")
        })
        box.addView(button("English") {
            setLocale("en")
        })
        box.addView(button("简体中文") {
            setLocale("zh")
        })

        val hint = TextView(ctx)
        hint.text = getString(R.string.lang_pack_hint)
        hint.textSize = 12f
        hint.setPadding(0, 20, 0, 8)
        box.addView(hint)

        box.addView(button(getString(R.string.lang_pack_scan)) {
            // ⚠️ `LangPack.load` 是通过 SAF 遍历 lang/ 目录、逐个读 JSON —— 磁盘 IO，
            // 之前在**主线程**跑，语言包多了点一下就卡住。
            android.widget.Toast.makeText(ctx, "正在扫描…", android.widget.Toast.LENGTH_SHORT).show()
            exec.execute {
                val n = LangPack.load(ctx)
                handler.post {
                    if (!isAdded) return@post
                    if (n <= 0) {
                        MaterialAlertDialogBuilder(ctx)
                            .setMessage(R.string.lang_pack_none)
                            .setPositiveButton(R.string.ok, null)
                            .show()
                    } else {
                        MaterialAlertDialogBuilder(ctx)
                            .setMessage(
                                getString(R.string.lang_pack_found, n.toString(), LangPack.loadedName)
                            )
                            .setPositiveButton(R.string.ok) { _, _ ->
                                try {
                                    activity?.recreate()
                                } catch (t: Throwable) { Err.ignore(t, "activity?.recreate()") }
                            }
                            .show()
                    }
                    refresh(tv)
                }
            }
        })

        box.addView(button("导出语言包模板") {
            // ⚠️ `LangPack.writeTemplate` 是 SAF 写文件（IO），之前在主线程跑。
            // 顺带：`val dir = WorkDir.uri(ctx)` 声明了**根本没用到**。
            exec.execute {
                val ok = runCatching { LangPack.writeTemplate(ctx) }.getOrDefault(false)
                handler.post {
                    if (!isAdded) return@post
                    MaterialAlertDialogBuilder(ctx)
                        .setMessage(
                            if (ok) "已生成 lang/template.json，改完重命名即可生效。需要重新点「扫描语言拓展包」加载。"
                            else "生成失败：工作目录不可用，请先设置工作目录。"
                        )
                        .setPositiveButton(R.string.ok, null)
                        .show()
                }
            }
        })

        box.addView(button("打开工作目录 lang 文件夹") {
            try {
                val u = WorkDir.uri(ctx)
                if (u.isNotBlank()) {
                    WebActivity.open(requireContext(), u, "")
                }
            } catch (t: Throwable) { Err.ignore(t, "WebActivity.open(requireContext(), u, \"\")") }
        })
        return scroll
    }

    private fun button(text: String, onClick: () -> Unit): Button {
        val ctx = requireContext()
        val b = com.google.android.material.button.MaterialButton(ctx)
        b.text = text
        b.setOnClickListener { onClick() }
        return b
    }

    private fun refresh(tv: TextView) {
        val ctx = requireContext()
        val code = Prefs.get(ctx).getString(K.LANG_CODE, "") ?: ""
        val sb = StringBuilder()
        sb.append("当前：").append(
            when {
                code == "en" -> "English"
                code == "zh" -> "简体中文"
                else -> getString(R.string.lang_system) + "（${Locale.getDefault().displayLanguage}）"
            }
        )
        if (LangPack.loadedName.isNotBlank()) {
            sb.append('\n').append("已加载拓展包：").append(LangPack.loadedName)
        }
        tv.text = sb.toString()
    }

    private fun setLocale(code: String) {
        val ctx = requireContext()
        Prefs.get(ctx).edit().putString(K.LANG_CODE, code).apply()
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                // ⚠️ Android 13+ 起应用内改 Locale 不再生效，必须走系统设置。
                // 但之前**直接跳走、一句提示都没有** —— 用户点「简体中文」，
                // 界面纹丝不动地跳到了一个系统页面，完全不知道要做什么，
                // 只会以为这个按钮坏了。
                android.widget.Toast.makeText(
                    ctx,
                    "Android 13 起应用不能自己改语言，请在打开的页面里把系统语言设为需要的语言。",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                startActivity(Intent(android.provider.Settings.ACTION_LOCALE_SETTINGS))
            } else {
                val l = if (code.isBlank()) Locale.getDefault() else Locale(code)
                Locale.setDefault(l)
                val cfg = resources.configuration
                // ⚠️ 之前只用 `cfg.setLocale(l)`：Android 7.0（API 24）起
                // 资源解析走的是 **LocaleList**，单改 setLocale 只影响第一个条目，
                // 在部分机型/部分资源上切不干净（表现为"切了英文但还有中文"）。
                // API 24+ 必须设 LocaleList。
                if (Build.VERSION.SDK_INT >= 24) {
                    cfg.setLocales(android.os.LocaleList(l))
                } else {
                    @Suppress("DEPRECATION")
                    cfg.setLocale(l)
                }
                @Suppress("DEPRECATION")
                resources.updateConfiguration(cfg, resources.displayMetrics)
                try {
                    activity?.recreate()
                } catch (t: Throwable) { Err.ignore(t, "activity?.recreate()") }
            }
        } catch (t: Throwable) { Err.ignore(t, "切换语言") }
    }
}
