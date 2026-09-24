package com.kongbai.modmigrator

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 收藏夹。
 *
 * 之前收藏只能「长按」触发，入口藏得太深，用户根本发现不了，
 * 而且没有一个能看收藏列表的地方——表现就是"收藏没法用"。
 * 现在：
 *   - 市场里每张卡片都有收藏按钮
 *   - 这里可以统一查看、安装、移除
 *   - 存储走本地+工作目录双写，没设工作目录也能用
 */
class FavoritesFragment : Fragment() {

    private lateinit var box: LinearLayout
    private lateinit var tvState: TextView

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

        root.addView(UiCards.hint(ctx, "长按卡片可以移除收藏。点「安装」会装到目标 mods 目录。"))

        tvState = TextView(ctx).apply {
            textSize = 12f
            setTextColor(resources.getColor(R.color.textSecondary, null))
        }
        root.addView(tvState)

        box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(box)
        return scroll
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val ctx = context ?: return
        val list = Favorites.list(ctx)
        box.removeAllViews()
        if (list.isEmpty()) {
            tvState.text = ""
            box.addView(
                UiCards.emptyCard(
                    ctx, "还没有收藏",
                    "在市场里点模组卡片上的「收藏」就会存在这里。\n" +
                        "收藏不需要先设置工作目录，会直接存在应用内。"
                )
            )
            return
        }
        tvState.text = "共 ${list.size} 个"
        for (m in list) {
            val card = UiCards.infoCard(
                ctx, R.drawable.ic_storefront,
                m.name,
                m.summary.take(60).ifBlank { "来自 ${m.source}" },
                "安装"
            ) { install(m) }
            card.setOnLongClickListener { remove(m) }
            box.addView(card)
        }
    }

    private fun remove(m: MarketMod): Boolean {
        val ctx = context ?: return false
        MaterialAlertDialogBuilder(ctx)
            .setTitle("移除收藏")
            .setMessage("确定不再收藏「${m.name}」？")
            .setPositiveButton("移除") { _, _ ->
                Favorites.remove(ctx, m)
                Toast.makeText(ctx, "已移除", Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        return true
    }

    private fun install(m: MarketMod) {
        val ctx = context ?: return
        Toast.makeText(ctx, "开始解析下载地址…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val files = if (m.source == "curseforge") {
                    BackendApi.files(ctx, m.id, "", "")
                } else {
                    ModrinthApi.versions(m.id.ifBlank { m.slug }, "", "")
                        .map {
                            com.kongbai.modmigrator.ModFile(
                                name = m.name, version = it.version,
                                url = it.url, fileName = it.fileName
                            )
                        }
                }
                val f = files.firstOrNull()
                if (f == null) {
                    main { Toast.makeText(ctx, "这个模组暂时没有可下载的文件", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                val dir = WorkDir.modsDir(ctx) ?: Targets.modsDir(ctx)
                if (dir == null) {
                    main { Toast.makeText(ctx, "请先设置工作目录", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                Downloader.download(ctx, f.url, dir, f.fileName, emptyMap())
                main { Toast.makeText(ctx, "已安装：${f.fileName}", Toast.LENGTH_SHORT).show() }
            } catch (t: Throwable) {
                main { Toast.makeText(ctx, "安装失败：${t.message}", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun main(b: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { b() }
    }
}
