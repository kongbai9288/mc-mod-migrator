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
            val n = LangPack.load(ctx)
            if (n <= 0) {
                MaterialAlertDialogBuilder(ctx)
                    .setMessage(R.string.lang_pack_none)
                    .setPositiveButton(R.string.ok, null)
                    .show()
            } else {
                MaterialAlertDialogBuilder(ctx)
                    .setMessage(getString(R.string.lang_pack_found, n.toString(), LangPack.loadedName))
                    .setPositiveButton(R.string.ok) { _, _ ->
                        try {
                            activity?.recreate()
                        } catch (t: Throwable) {
                        }
                    }
                    .show()
            }
            refresh(tv)
        })

        box.addView(button("导出语言包模板") {
            LangPack.writeTemplate(ctx)
            val dir = WorkDir.uri(ctx)
            MaterialAlertDialogBuilder(ctx)
                .setMessage("已生成 lang/template.json，改完重命名即可生效。需要重新点「扫描语言拓展包」加载。")
                .setPositiveButton(R.string.ok, null)
                .show()
        })

        box.addView(button("打开工作目录 lang 文件夹") {
            try {
                val u = WorkDir.uri(ctx)
                if (u.isNotBlank()) {
                    WebActivity.open(requireContext(), u, "")
                }
            } catch (t: Throwable) {
            }
        })
        return scroll
    }

    private fun button(text: String, onClick: () -> Unit): Button {
        val ctx = requireContext()
        val b = Button(ctx)
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
        // Android 13+ 走系统设置，其余用 Locale 直接切
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                startActivity(Intent(android.provider.Settings.ACTION_LOCALE_SETTINGS))
            } else {
                val l = if (code.isBlank()) Locale.getDefault() else Locale(code)
                Locale.setDefault(l)
                val cfg = resources.configuration
                cfg.setLocale(l)
                @Suppress("DEPRECATION")
                resources.updateConfiguration(cfg, resources.displayMetrics)
                try {
                    activity?.recreate()
                } catch (t: Throwable) {
                }
            }
        } catch (t: Throwable) {
        }
    }
}
