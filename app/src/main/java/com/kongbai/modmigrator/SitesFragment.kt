package com.kongbai.modmigrator

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment

/**
 * 常用 MC 站点导航。
 * 用内置浏览器打开，长按可以用系统浏览器打开。
 */
class SitesFragment : Fragment() {

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

        root.addView(
            UiCards.hint(
                ctx,
                "点击用内置浏览器打开，长按用系统浏览器打开。\n" +
                    "内置浏览器里遇到下载链接会自动交给系统处理。"
            )
        )

        for (g in SitesConfig.groups()) {
            val list = SitesConfig.byGroup(g)
            if (list.isEmpty()) continue
            root.addView(UiCards.sectionTitle(ctx, g))
            for (s in list) {
                val card = UiCards.infoCard(ctx, R.drawable.ic_open_in_new, s.name, s.desc) {
                    WebActivity.open(ctx, s.url, s.name)
                }
                card.setOnLongClickListener {
                    try {
                        startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(s.url)
                            )
                        )
                    } catch (t: Throwable) { Err.ignore(t, ")") }
                    true
                }
                root.addView(card)
            }
        }
        return scroll
    }
}
