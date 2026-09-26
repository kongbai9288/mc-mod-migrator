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
        // ⚠️ 两个问题：
        //  ① `LogCenter.readAll(ctx)` 是通过 ContentResolver **读文件**，
        //     属于磁盘 IO，却直接在**主线程**调用 → 日志文件大了会卡顿甚至 ANR。
        //  ② 它是**无条件**读的：`disk` 只有在 mem 为空时才用到，
        //     但不管 mem 有没有内容都会读一遍磁盘，纯属浪费。
        // 现在：先把内存里的部分显示出来（立刻有反馈），
        // 只有内存为空时才去后台读磁盘。
        val mem = LogCenter.all()
        tvInfo.text = "共 ${LogCenter.count()} 条（内存中）"
        if (mem.isNotEmpty()) {
            tv.text = tail(mem.joinToString("\n") { it.toString() })
            return
        }
        tv.text = "正在读取磁盘日志…"
        readDisk(ctx) { disk ->
            tv.text = tail(disk.ifBlank { "暂无日志" })
            tvInfo.text = "共 ${LogCenter.count()} 条（内存中）· 日志目录：${
                WorkDir.logs(ctx)?.name ?: "未设置工作目录"
            }"
        }
    }

    /** 只看错误，方便定位问题 */
    private fun showErrors() {
        val ctx = context ?: return
        val errs = LogCenter.all().filter { it.isError }
        if (errs.isNotEmpty()) {
            tv.text = errs.joinToString("\n") { it.toString() }
            tvInfo.text = "错误 ${errs.size} 条"
            return
        }
        tv.text = "正在读取磁盘日志…"
        readDisk(ctx) { disk ->
            val lines = disk.split("\n").filter { it.contains("错误") }
            tv.text = lines.joinToString("\n").ifBlank { "没有错误记录" }
            tvInfo.text = "内存中无错误"
        }
    }

    /** 后台读磁盘日志，读完切回主线程 */
    private fun readDisk(ctx: android.content.Context, onDone: (String) -> Unit) {
        exec.execute {
            val disk = LogCenter.readAll(ctx)
            handler.post {
                if (!isAdded) return@post
                onDone(disk)
            }
        }
    }

    private val exec = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** 只显示末尾若干行，避免超长文本卡界面 */
    private fun tail(s: String): String {
        val lines = s.split("\n")
        return if (lines.size > 400) lines.takeLast(400).joinToString("\n") else s
    }
    override fun onDestroyView() {
        super.onDestroyView()
        // 清理 Handler：页面销毁后若还有未执行的 post，
        // 回调里访问已销毁的 View 会直接崩。
        runCatching { handler.removeCallbacksAndMessages(null) }
    }

}
