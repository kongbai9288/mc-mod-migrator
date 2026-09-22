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
        items.clear()
        items.addAll(PluginApi.discover(requireContext()))
        adapter.notifyDataSetChanged()
        tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (items.isEmpty()) {
            tvEmpty.text = "还没发现插件。\n插件是独立 APK，声明响应 ${PluginApi.ACTION_QUERY} 的接收器即可被识别。"
        }
    }

    private fun runPlugin(p: PluginApi.Plugin) {
        val acts = arrayOf("ping", "scan_instance", "export", "import")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(p.label)
            .setItems(acts) { _, w ->
                val r = PluginApi.run(requireContext(), p.pkg, acts[w], "")
                Toast.makeText(
                    requireContext(),
                    if (r.isNullOrBlank()) "已发送 ${acts[w]}（插件通过广播异步回传）" else r,
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
    }
}
