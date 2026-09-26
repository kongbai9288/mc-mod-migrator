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
    private lateinit var cbAll: android.widget.CheckBox
    private lateinit var btnBatch: View
    /** 「保留天数」按钮，改完只更新它的文字，不重建 Activity */
    private lateinit var opDays: View
    /** 当前勾选的回收站条目 */
    private val picked = HashSet<String>()

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

        // 操作按钮用 MaterialButton（原生 Button 不跟主题色，会一直是灰色），
        // 两个按钮一行等宽，间距用 dp 而不是写死像素
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        fun opBtn(text: String, filled: Boolean, act: () -> Unit): View {
            val b = if (filled) UiCards.button(ctx, text) else UiCards.outlinedButton(ctx, text)
            b.setOnClickListener { act() }
            val lp = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            lp.marginEnd = (6 * resources.displayMetrics.density).toInt()
            b.layoutParams = lp
            return b
        }
        opDays = opBtn("保留天数：${Trash.days(ctx)} 天", false) { pickDays() }
        row.addView(opDays)
        val emptyBtn = opBtn(getString(R.string.trash_empty_all), false) { confirmEmpty() }
        (emptyBtn.layoutParams as LinearLayout.LayoutParams).marginEnd = 0
        row.addView(emptyBtn)
        root.addView(row)

        // 批量操作栏：全选 / 还原所选 / 彻底删除所选
        val batchRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        cbAll = android.widget.CheckBox(ctx).apply {
            text = "全选"
            textSize = 13f
        }
        batchRow.addView(cbAll)
        batchRow.addView(android.widget.Space(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        btnBatch = opBtn("还原所选", true) { restoreSelected() }
        batchRow.addView(btnBatch)

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
            refresh()
        }
    }

    /**
     * 读回收站清单。
     *
     * ⚠️ `Trash.items()` 是**读磁盘上的 JSON 索引**，`totalSize` 还要遍历统计，
     * 都是 IO。之前 `render()` 直接在主线程调它们，
     * 条目多了就会卡顿；而 `deleteForever` / `empty` 是通过 SAF 删文件，
     * 更不该在主线程跑（清空几十个文件会直接 ANR）。
     * 现在统一：IO 放后台，只把结果带回主线程渲染。
     */
    private fun refresh() {
        val ctx = context ?: return
        exec.execute {
            val items = Trash.items(ctx)
            val total = runCatching { Trash.totalSize(ctx) }.getOrDefault(0L)
            safePost(handler) { if (isAdded) render(items, total) }
        }
    }

    private fun render(items: List<Trash.Item>, total: Long) {
        val ctx = context ?: return
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
        picked.retainAll(items.map { it.name }.toSet())
        for (item in items) {
            // 每条做成"勾选框 + 卡片"，既能单选还原，也能批量操作
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val cb = android.widget.CheckBox(ctx).apply {
                isChecked = picked.contains(item.name)
                setOnCheckedChangeListener { _, on ->
                    if (on) picked.add(item.name) else picked.remove(item.name)
                    updateBatchBtn()
                }
            }
            line.addView(cb)
            line.addView(
                UiCards.infoCard(
                    ctx, R.drawable.ic_delete,
                    // 显示原始文件名；回收站里那份如果带序号，在副行说明，
                    // 否则用户会看到 sodium(1).jar 却不知道它还原出来叫什么
                    item.restoreName,
                    buildString {
                        append("删除于 ${fmt.format(Date(item.at))} · ${item.size / 1024} KB")
                        if (item.name != item.restoreName) append(" · 回收站内名：${item.name}")
                    },
                    "还原"
                ) { doRestore(item) }.also { card ->
                    card.setOnLongClickListener { doDelete(item) }
                }
            )
            box.addView(line)
        }

        cbAll.setOnCheckedChangeListener(null)
        cbAll.isChecked = picked.size == items.size && items.isNotEmpty()
        cbAll.setOnCheckedChangeListener { _, on ->
            if (on) picked.addAll(items.map { it.name }) else picked.clear()
            refresh()
        }
        updateBatchBtn()
    }

    private fun updateBatchBtn() {
        if (!::btnBatch.isInitialized) return
        val n = picked.size
        (btnBatch as? android.widget.TextView)?.text =
            if (n > 0) "还原所选（$n）" else "还原所选"
        btnBatch.isEnabled = n > 0
        btnBatch.alpha = if (n > 0) 1f else 0.4f
    }

    /** 批量还原 */
    private fun restoreSelected() {
        val ctx = context ?: return
        if (picked.isEmpty()) return
        val names = picked.toList()
        Toast.makeText(ctx, "正在还原 ${names.size} 个…", Toast.LENGTH_SHORT).show()
        exec.execute {
            var ok = 0
            // ⚠️ 之前 `Trash.items(ctx)` 写在**循环里** —— 每还原一个
            // 就把整个索引 JSON 重新读一遍。还原 20 个 = 读 20 次文件。
            // 而且每还原一个都会改写一次索引，读到的还是上一版快照。
            // 改成：先读一次清单，循环里只做还原。
            val all = Trash.items(ctx)
            for (n in names) {
                val item = all.firstOrNull { it.name == n }
                if (item != null && Trash.restore(ctx, item)) ok++
            }
            safePost(handler) {
                Toast.makeText(ctx, "已还原 $ok / ${names.size}", Toast.LENGTH_SHORT).show()
                picked.clear()
                refresh()
            }
        }
    }

    private fun doRestore(item: Trash.Item) {
        val ctx = context ?: return
        exec.execute {
            val ok = Trash.restore(ctx, item)
            safePost(handler) {
                Toast.makeText(ctx, if (ok) "已还原" else "还原失败（原目录可能已不可用）", Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
    }

    private fun doDelete(item: Trash.Item): Boolean {
        val ctx = context ?: return false
        MaterialAlertDialogBuilder(ctx)
            .setTitle("彻底删除")
            .setMessage("「${item.name}」将被永久删除，无法还原。")
            .setPositiveButton(getString(R.string.trash_delete)) { _, _ ->
                // SAF 删除是 IO，放后台
                exec.execute {
                    Trash.deleteForever(ctx, item)
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        return true
    }

    private fun confirmEmpty() {
        val ctx = context ?: return
        // ⚠️ `Trash.count(ctx)` 是读磁盘 JSON（IO），之前在**主线程**调，
        // 只是弹个确认框就顺带读一遍文件。
        exec.execute {
            val n0 = Trash.count(ctx)
            safePost(handler) {
                if (!isAdded) return@safePost
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("清空回收站")
                    .setMessage("回收站里 $n0 个文件会被永久删除。")
                    .setPositiveButton("清空") { _, _ ->
                        exec.execute {
                            val n = Trash.empty(ctx)
                            safePost(handler) {
                                Toast.makeText(ctx, "已清空 $n 个", Toast.LENGTH_SHORT).show()
                                refresh()
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
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
                // ⚠️ 之前用 `activity?.recreate()` —— 改个保留天数
                // 就把整个 Activity 重建一遍：屏幕闪一下、滚动位置丢掉，
                // 而且如果是在 SettingsHostActivity 里打开的，
                // recreate 还会把返回栈一起清掉，直接退回主界面。
                // 这里只需要更新那个按钮的文字，不需要重建。
                (opDays as? android.widget.TextView)?.text = "保留天数：${Trash.days(ctx)} 天"
                Toast.makeText(ctx, "已设为 ${vals[w]} 天", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    override fun onDestroyView() {
        super.onDestroyView()
        // 清理 Handler：页面销毁后若还有未执行的 post，
        // 回调里访问已销毁的 View 会直接崩。
        runCatching { handler.removeCallbacksAndMessages(null) }
    }

}
