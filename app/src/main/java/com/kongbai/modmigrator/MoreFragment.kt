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
        val inNav = NavConfig.keys(ctx0)
        val pages = NavConfig.morePages(ctx0)

        for (p in pages) {
            val row = LinearLayout(ctx0)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, (10 * resources.displayMetrics.density).toInt(), 0, 0)

            val title = TextView(ctx0)
            title.text = getString(p.titleRes)
            title.textSize = 15f
            title.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            row.addView(title)

            val btnMove = Button(ctx0)
            btnMove.text = getString(R.string.nav_move_to_bar)
            btnMove.setOnClickListener {
                val cur = NavConfig.keys(ctx0).toMutableList()
                if (cur.size >= NavConfig.MAX_CUSTOM) {
                    MaterialAlertDialogBuilder(ctx0)
                        .setMessage(R.string.nav_limit)
                        .setPositiveButton(R.string.ok, null)
                        .show()
                    return@setOnClickListener
                }
                cur.add(p.key)
                NavConfig.save(ctx0, cur)
                // 设置页是 Activity，需要重建主界面才能刷新导航栏
                try {
                    activity?.recreate()
                } catch (t: Throwable) {
                }
            }
            row.addView(btnMove)

            val btnOpen = Button(ctx0)
            btnOpen.text = getString(R.string.tab_more)
            btnOpen.setOnClickListener { openPage(p) }
            row.addView(btnOpen)

            box.addView(row)
        }

        if (pages.isEmpty()) {
            val tv = TextView(ctx0)
            tv.text = getString(R.string.empty_hint)
            box.addView(tv)
        }
        return scroll
    }

    private fun openPage(p: NavConfig.Page) {
        try {
            val f = p.make()
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, f)
                .addToBackStack(null)
                .commit()
        } catch (t: Throwable) {
        }
    }
}
