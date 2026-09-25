package com.kongbai.modmigrator

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 「更多」页：所有没放到底部导航栏的功能都在这里。
 *
 * 每一项都可以「移到底部导航栏」；在设置 → 导航栏自定义里可以反向移回来。
 * 视图全部用代码构建，不新增布局文件，避免 id 冲突。
 */
class MoreFragment : Fragment() {

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

        val hint = TextView(ctx)
        hint.text = getString(R.string.more_hint)
        hint.textSize = 12f
        box.addView(hint)

        val ctx0 = ctx
        val pages = NavConfig.more(ctx0)

        if (pages.isEmpty()) {
            box.addView(
                UiCards.emptyCard(
                    ctx0, "所有功能都在底部栏了",
                    "到「设置 → 底部导航栏」可以把功能移回这里。"
                )
            )
        }
        for (p in pages) {
            val card = UiCards.infoCard(
                ctx0,
                p.icon,
                getString(p.title),
                "在「更多」里 · 长按可移到底部导航栏",
                "打开"
            ) { openPage(p) }
            card.setOnLongClickListener { moveToBottom(p.key) }
            box.addView(card)
        }

        return scroll
    }

    /** 长按卡片：把这一项移到底部导航栏 */
    private fun moveToBottom(key: String): Boolean {
        val ctx = context ?: return false
        val ok = NavConfig.moveToBottom(ctx, key)
        android.widget.Toast.makeText(
            ctx,
            if (ok) "已移到底部导航栏" else "底部最多 4 个，先移一个出来",
            android.widget.Toast.LENGTH_SHORT
        ).show()
        if (ok) {
            (activity as? MainActivity)?.rebuildNav()
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, MoreFragment())
                .commit()
        }
        return true
    }

    private fun openPage(p: NavConfig.Item) {
        try {
            val f = p.make()
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, f)
                .addToBackStack(null)
                .commit()
        } catch (t: Throwable) { Err.ignore(t, ".commit()") }
    }
}
