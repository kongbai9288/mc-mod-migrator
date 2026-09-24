package com.kongbai.modmigrator

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 统一的下载进度弹窗。
 *
 * 之前各处下载都是"点一下 → 转圈 → 出结果"，中间没有任何进度，
 * 大文件看起来像卡死了。现在所有下载都走这里，显示：
 *   文件名 / 已下载 / 总量 / 百分比 / 速度
 *
 * 用法：
 *   val d = ProgressDialog.show(ctx, "xxx.jar")
 *   d.update(done, total)
 *   d.dismiss()
 */
class ProgressDialog private constructor(
    private val ctx: Context,
    private val name: String
) {

    private lateinit var bar: ProgressBar
    private lateinit var tv: TextView
    private var dlg: androidx.appcompat.app.AlertDialog? = null

    private var startAt = System.currentTimeMillis()
    private var lastDone = 0L
    private var lastAt = startAt

    companion object {
        fun show(ctx: Context, name: String): ProgressDialog {
            val p = ProgressDialog(ctx, name)
            p.build()
            return p
        }
    }

    private fun build() {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * ctx.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(ctx).apply {
            text = name
            textSize = 14f
            setPadding(0, 0, 0, (8 * ctx.resources.displayMetrics.density).toInt())
        })
        bar = ProgressBar(
            ctx, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            isIndeterminate = true
        }
        root.addView(bar)
        tv = TextView(ctx).apply {
            text = "准备中…"
            textSize = 12f
            gravity = Gravity.START
            setPadding(0, (6 * ctx.resources.displayMetrics.density).toInt(), 0, 0)
        }
        root.addView(tv)

        dlg = MaterialAlertDialogBuilder(ctx)
            .setTitle("下载中")
            .setView(root)
            .setCancelable(false)
            .show()
    }

    /** 更新进度。done/total 单位字节；total<=0 表示未知大小 */
    fun update(done: Long, total: Long) {
        try {
            val now = System.currentTimeMillis()
            val dt = now - lastAt
            val db = done - lastDone
            val speed = if (dt > 400 && db > 0) {
                (db * 1000.0 / dt / 1024).let { String.format("%.0f KB/s", it) }
            } else ""

            if (total > 0) {
                bar.isIndeterminate = false
                val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                bar.progress = pct
                tv.text = buildString {
                    append(mb(done)).append(" / ").append(mb(total))
                    append("  ").append(pct).append('%')
                    if (speed.isNotBlank()) append("  ").append(speed)
                }
            } else {
                tv.text = buildString {
                    append(mb(done))
                    if (speed.isNotBlank()) append("  ").append(speed)
                }
            }
            if (dt > 400) {
                lastAt = now
                lastDone = done
            }
        } catch (t: Throwable) {
        }
    }

    fun dismiss() {
        try {
            dlg?.dismiss()
        } catch (t: Throwable) {
        }
    }

    private fun mb(b: Long): String {
        val v = b / 1048576.0
        return if (v >= 1) String.format("%.1f MB", v)
        else String.format("%.0f KB", b / 1024.0)
    }
}
