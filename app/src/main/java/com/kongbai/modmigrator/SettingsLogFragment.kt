package com.kongbai.modmigrator

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * 设置 → 运行日志。
 *
 * 页面顶部只显示条数与最新几条，完整日志写在这里查看。
 * 日志文件落在用户授权的工作目录（WorkDir/logs/app.log）。
 */
class SettingsLogFragment : Fragment() {

    private lateinit var tv: TextView
    private lateinit var tvInfo: TextView
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_settings_log, container, false)
        tv = v.findViewById(R.id.tvLog)
        tvInfo = v.findViewById(R.id.tvLogInfo)
        v.findViewById<Button>(R.id.btnRefresh).setOnClickListener { refresh() }
        v.findViewById<Button>(R.id.btnClear).setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            LogCenter.clearFile(ctx)
            refresh()
        }
        v.findViewById<Button>(R.id.btnErrors).setOnClickListener { showErrors() }
        refresh()
        return v
    }

    private fun refresh() {
        val ctx = context ?: return
        val mem = LogCenter.all()
        val disk = LogCenter.readAll(ctx)
        val text = if (mem.isNotEmpty()) {
            mem.joinToString("\n") { it.toString() }
        } else {
            disk.ifBlank { "暂无日志" }
        }
        tv.text = tail(text)
        tvInfo.text = "共 ${LogCenter.count()} 条（内存中）· 日志目录：${WorkDir.logs(ctx)?.name ?: "未设置工作目录"}"
    }

    /** 只看错误，方便定位问题 */
    private fun showErrors() {
        val errs = LogCenter.all().filter { it.isError }
        val ctx = context ?: return
        val disk = LogCenter.readAll(ctx).split("\n").filter { it.contains("错误") }
        tv.text = if (errs.isNotEmpty()) {
            errs.joinToString("\n") { it.toString() }
        } else {
            disk.joinToString("\n").ifBlank { "没有错误记录" }
        }
        tvInfo.text = "错误 ${errs.size} 条"
    }

    /** 只显示末尾若干行，避免超长文本卡界面 */
    private fun tail(s: String): String {
        val lines = s.split("\n")
        return if (lines.size > 400) lines.takeLast(400).joinToString("\n") else s
    }
}
