package com.kongbai.modmigrator

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

/** 设置 → 主题色 */
class SettingsThemeFragment : Fragment() {

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

        val cur = ThemePrefs.index(ctx)
        for ((i, t) in ThemePrefs.themes().withIndex()) {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(0, 8, 0, 8)

            val tv = TextView(ctx)
            tv.text = if (i == cur) "✓ ${t.name}" else t.name
            tv.textSize = 15f
            tv.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            row.addView(tv)

            val b = Button(ctx)
            b.text = getString(R.string.theme_apply)
            b.textSize = 11f
            b.setOnClickListener {
                ThemePrefs.save(ctx, i)
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(t.name)
                    .setMessage(R.string.theme_apply)
                    .setPositiveButton(R.string.ok) { _, _ ->
                        try {
                            activity?.recreate()
                        } catch (e: Throwable) {
                        }
                    }
                    .show()
            }
            row.addView(b)
            box.addView(row)
        }
        return scroll
    }
}
