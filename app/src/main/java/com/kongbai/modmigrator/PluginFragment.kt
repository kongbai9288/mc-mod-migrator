package com.kongbai.modmigrator

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** 插件页：列出已发现的插件，可手动触发动作 */
class PluginFragment : Fragment() {

    private val items = mutableListOf<PluginApi.Plugin>()
    private lateinit var adapter: PluginAdapter
    private lateinit var tvEmpty: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_plugin, container, false)
        tvEmpty = v.findViewById(R.id.tvEmpty)
        val rv = v.findViewById<RecyclerView>(R.id.rvPlugins)
        adapter = PluginAdapter(items) { p -> runPlugin(p) }
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter
        v.findViewById<Button>(R.id.btnScan).setOnClickListener { scan() }
        scan()
        return v
    }

    private fun scan() {
        // discover() 内部会给每个插件发一次广播等回传（最多 3 秒/个），
        // 必须放后台，否则点「扫描」会把主线程卡住甚至 ANR。
        val ctx = requireContext().applicationContext
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            val found = PluginApi.discover(ctx)
            h.post {
                if (!isAdded) return@post
                items.clear()
                items.addAll(found)
                adapter.notifyDataSetChanged()
                tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                if (items.isEmpty()) {
                    tvEmpty.text = "还没发现插件。\n插件是独立 APK，声明响应 ${PluginApi.ACTION_QUERY} 的接收器即可被识别。"
                }
            }
        }.start()
    }

    private fun runPlugin(p: PluginApi.Plugin) {
        // ── 用插件**真实**回传的动作列表，不再用宿主写死的假名字 ──
        // 之前这里写死 "ping/scan_instance/export/import"，
        // 那是宿主单方面猜的，真实插件一个都不支持的话
        // 点了就永远只是"已发送"，什么也不会发生。
        val acts = p.actions.filter { it.isNotBlank() }.toTypedArray()
        if (acts.isEmpty()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(p.label)
                .setMessage(
                    "这个插件没有报告它支持哪些动作。\n\n" +
                        "插件需要在响应 ${PluginApi.ACTION_QUERY} 时，" +
                        "用 setResultData 回传动作列表（用 \"${PluginApi.ACT_SEP}\" 分隔）。"
                )
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(p.label)
            .setItems(acts) { _, w ->
                val a = acts[w]
                // PluginApi.run 会等结果，**必须在后台线程调用**
                val h = android.os.Handler(android.os.Looper.getMainLooper())
                Thread {
                    val r = PluginApi.run(requireContext().applicationContext, p.pkg, a, "")
                    h.post {
                        if (!isAdded) return@post
                        Toast.makeText(
                            requireContext(),
                            if (r.isNullOrBlank()) {
                                "已发送「$a」，但插件没有回传结果（可能不支持该动作或未响应）"
                            } else r,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }.start()
            }
            .show()
    }
}
