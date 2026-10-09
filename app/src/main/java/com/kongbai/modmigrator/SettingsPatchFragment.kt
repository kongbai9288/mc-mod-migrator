package com.kongbai.modmigrator

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import java.util.concurrent.Executors

/**
 * 补丁。
 *
 * 这里列出所有可以远程更新的数据分类，每一个都能单独刷新。
 *
 * 之所以要做成界面而不是静默后台更新：用户反馈"大概率永远遇不到这个功能"——
 * 自动更新 6 小时一次、失败了又什么都不说，等于没有。
 * 现在把状态摆出来：每项显示内置条数、远端条数、上次更新时间，
 * 手动刷新立刻执行并把结果直接写出来。
 */
class SettingsPatchFragment : Fragment() {

    private val exec = Bg.io
    private val handler = Bg.ui
    private lateinit var box: LinearLayout

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
                "应用内置的数据会随 MC 与启动器变化而过期。下面的内容可以从仓库" +
                    "单独更新，装了旧版本也能拿到新数据，不需要重装应用。"
            )
        )

        box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(box)

        root.addView(MaterialButton(ctx).apply {
            text = "全部刷新"
            setOnClickListener { refreshAll() }
        })

        render()
        return scroll
    }

    private fun render() {
        val ctx = context ?: return
        box.removeAllViews()
        for (k in PatchCenter.all()) {
            val at = PatchCenter.lastAt(ctx, k)
            val state = buildString {
                append("内置 ${k.builtinCount} 项")
                if (k.remoteCount > 0) append(" · 远端 ${k.remoteCount} 项")
                append("\n上次更新：${PatchCenter.timeText(at)}")
                if (k.lastError.isNotBlank()) append("\n${k.lastError}")
            }
            box.addView(
                UiCards.infoCard(
                    ctx, R.drawable.ic_cloud_sync, "${k.title}（${k.key}）",
                    "${k.desc}\n$state", "刷新"
                ) { refreshOne(k) }
            )
        }
        if (PatchCenter.all().isEmpty()) {
            box.addView(UiCards.emptyCard(ctx, "暂无可更新的内容", ""))
        }
    }

    private fun refreshOne(k: PatchCenter.Kind) {
        val ctx = context ?: return
        Tips.short(ctx, "正在刷新「${k.title}」…")
        exec.execute {
            val ok = PatchCenter.update(ctx, k, force = true)
            handler.post {
                if (!isAdded) return@post
                render()
                Tips.long(ctx, if (ok) "「${k.title}」已更新，共 ${k.remoteCount} 项"
                    else "「${k.title}」更新失败：${k.lastError}")
            }
        }
    }

    private fun refreshAll() {
        val ctx = context ?: return
        Tips.short(ctx, "正在刷新全部…")
        exec.execute {
            var ok = 0
            for (k in PatchCenter.all()) {
                if (PatchCenter.update(ctx, k, force = true)) ok++
            }
            handler.post {
                if (!isAdded) return@post
                render()
                Tips.long(ctx, "完成：$ok / ${PatchCenter.all().size} 项已更新")
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        runCatching { handler.removeCallbacksAndMessages(null) }
        // 全局共享池，不 shutdown
        handler.removeCallbacksAndMessages(null)
    }
}
