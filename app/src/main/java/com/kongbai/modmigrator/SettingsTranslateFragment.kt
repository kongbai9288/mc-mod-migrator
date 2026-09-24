package com.kongbai.modmigrator

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton

/**
 * 翻译设置。
 *
 * 用 Google ML Kit 的离线模型。这里能看到模型状态、
 * 手动下载模型、并直接试翻一段文字确认效果。
 */
class SettingsTranslateFragment : Fragment() {

    private lateinit var tvState: TextView
    private lateinit var etTest: EditText
    private lateinit var tvResult: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val scroll = android.widget.ScrollView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(root)

        root.addView(UiCards.hint(ctx, getString(R.string.translate_hint)))

        tvState = TextView(ctx).apply {
            textSize = 13f
            setTextColor(resources.getColor(R.color.textSecondary, null))
            setPadding(0, 4, 0, 12)
        }
        root.addView(tvState)

        root.addView(MaterialButton(ctx).apply {
            text = getString(R.string.translate_download)
            setOnClickListener { downloadModel() }
        })

        root.addView(MaterialButton(ctx).apply {
            text = getString(R.string.translate_test)
            setOnClickListener { testTranslate() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (8 * resources.displayMetrics.density).toInt()
            layoutParams = lp
        })

        etTest = EditText(ctx).apply {
            setHint("A mod that adds many new ores and tools to your world.")
            setText(android.text.SpannableStringBuilder(
                "A mod that adds many new ores and tools to your world."
            ))
            setSingleLine(false)
            maxLines = 4
            textSize = 13f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (12 * resources.displayMetrics.density).toInt()
            layoutParams = lp
        }
        root.addView(etTest)

        tvResult = TextView(ctx).apply {
            textSize = 14f
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
            setLineSpacing(0f, 1.35f)
        }
        root.addView(tvResult)

        refreshState()
        return scroll
    }

    private fun refreshState() {
        val ready = Translator.isReady()
        tvState.text = getString(R.string.translate_model_state) + "：" +
            if (ready) getString(R.string.translate_ready)
            else getString(R.string.translate_not_ready)
    }

    private fun downloadModel() {
        val ctx = context ?: return
        Toast.makeText(ctx, getString(R.string.translate_downloading), Toast.LENGTH_SHORT).show()
        Translator.ensureModel(ctx) { ok ->
            if (!isAdded) return@ensureModel
            refreshState()
            Toast.makeText(
                ctx,
                if (ok) "模型下载完成，现在可以离线翻译了"
                else "下载失败，请检查网络（模型约 30MB）",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun testTranslate() {
        val ctx = context ?: return
        val text = etTest.text.toString().trim()
        if (text.isBlank()) return
        tvResult.text = "翻译中…"
        OfflineTranslate.translate(ctx, text) { zh ->
            if (!isAdded) return@translate
            tvResult.text = zh ?: "翻译失败：模型未下载且当前无法联网"
        }
    }
}
