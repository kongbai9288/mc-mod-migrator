package com.kongbai.modmigrator

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 回收站。
 * 能看、能还原、能彻底删除、能清空，保留天数可在这里改。
 */
class TrashFragment : Fragment() {

    private val exec = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
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

        root.addView(
            UiCards.hint(
                ctx,
                "删掉的模组先放这里，过期自动清掉。点卡片可还原或彻底删除。"
            )
        )

        tvState = TextView(ctx).apply {
            textSize = 12f
            setTextColor(resources.getColor(R.color.textSecondary, null))
            setPadding(0, 4, 0, 8)
        }
        root.addView(tvState)

        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(android.widget.Button(ctx).apply {
            text = "保留天数：${Trash.days(ctx)} 天"
            setOnClickListener { pickDays() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(android.widget.Button(ctx).apply {
            text = getString(R.string.trash_empty_all)
            setOnClickListener { confirmEmpty() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(row)

        box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        root.addView(box)
        return scroll
    }

    override fun onResume() {
        super.onResume()
        // 顺手清一次过期的
        exec.execute {
            runCatching { Trash.purgeExpired(context ?: return@execute) }
            safePost(handler) { render() }
        }
    }

    private fun render() {
        val ctx = context ?: return
        val items = Trash.items(ctx)
        box.removeAllViews()
        if (items.isEmpty()) {
            tvState.text = ""
            box.addView(
                UiCards.emptyCard(ctx, getString(R.string.trash_empty), "删掉模组后会出现在这里。")
            )
            return
        }
        val mb = Trash.totalSize(ctx) / 1048576.0
        tvState.text = "共 ${items.size} 个 · ${String.format("%.1f MB", mb)}"
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        for (it in items) {
            box.addView(
                UiCards.infoCard(
                    ctx, R.drawable.ic_delete,
                    it.name,
                    "删除于 ${fmt.format(Date(it.at))} · ${it.size / 1024} KB",
                    "还原"
                ) { doRestore(it) }
            val card = box.getChildAt(box.childCount - 1)
            card.setOnLongClickListener { doDelete(it) }
            )
        }
    }

    private fun doRestore(item: Trash.Item) {
        val ctx = context ?: return
        exec.execute {
            val ok = Trash.restore(ctx, item)
            safePost(handler) {
                Toast.makeText(ctx, if (ok) "已还原" else "还原失败（原目录可能已不可用）", Toast.LENGTH_SHORT).show()
                render()
            }
        }
    }

    private fun doDelete(item: Trash.Item): Boolean {
        val ctx = context ?: return false
        MaterialAlertDialogBuilder(ctx)
            .setTitle("彻底删除")
            .setMessage("「${item.name}」将被永久删除，无法还原。")
            .setPositiveButton(getString(R.string.trash_delete)) { _, _ ->
                Trash.deleteForever(ctx, item)
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        return true
    }

    private fun confirmEmpty() {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle("清空回收站")
            .setMessage("回收站里 ${Trash.count(ctx)} 个文件会被永久删除。")
            .setPositiveButton("清空") { _, _ ->
                val n = Trash.empty(ctx)
                Toast.makeText(ctx, "已清空 $n 个", Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickDays() {
        val ctx = context ?: return
        val opts = arrayOf("1 天", "3 天", "7 天", "15 天", "30 天", "90 天")
        val vals = intArrayOf(1, 3, 7, 15, 30, 90)
        val cur = Trash.days(ctx)
        val idx = vals.indexOfFirst { it == cur }.let { if (it >= 0) it else 2 }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("回收站保留天数")
            .setSingleChoiceItems(opts, idx) { d, w ->
                Trash.setDays(ctx, vals[w])
                d.dismiss()
                activity?.recreate()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
