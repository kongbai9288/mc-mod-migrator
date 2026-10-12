package com.kongbai.modmigrator

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 插件页：列出插件目录里的插件，可运行、可开关。
 *
 * 插件是**目录里的文件**（manifest.json + 脚本），不是独立 APK。
 * 扫描是读目录，很快，直接放后台跑；运行是跑脚本，
 * 可能连网或读写文件，必须在后台跑。
 */
class PluginFragment : Fragment() {

    private val items = mutableListOf<PluginHost.Plugin>()
    private lateinit var adapter: PluginAdapter
    private lateinit var tvEmpty: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_plugin, container, false)
        tvEmpty = v.findViewById(R.id.tvEmpty)
        val rv = v.findViewById<RecyclerView>(R.id.rvPlugins)
        adapter = PluginAdapter(items) { p -> showPluginMenu(p) }
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter
        v.findViewById<Button>(R.id.btnScan).setOnClickListener { scan() }
        tvEmpty.setOnClickListener { showHelp() }
        scan()
        return v
    }

    private fun scan() {
        val ctx = requireContext().applicationContext
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            val found = PluginHost.scan(ctx)
            h.post {
                if (!isAdded) return@post
                items.clear()
                items.addAll(found)
                adapter.notifyDataSetChanged()
                tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                if (items.isEmpty()) {
                    tvEmpty.text = buildString {
                        append("插件目录里还没有插件。\n\n")
                        append(PluginHost.dir(ctx).absolutePath)
                        append("\n\n插件是一个子目录：manifest.json 加一个 js 脚本。")
                        append("\n点这里看完整说明，或写入三个内置示例。")
                    }
                    // 第一次进来目录是空的，与其让用户对着说明猜，
                    // 不如直接给能跑的示例，跑通了再照着改。
                    offerSamples()
                }
            }
        }.start()
    }

    private fun offerSamples() {
        val ctx = context ?: return
        if (!isAdded) return
        MaterialAlertDialogBuilder(ctx)
            .setTitle("写入示例插件")
            .setMessage("会在插件目录里写入三个可运行的示例（打招呼 / 列目录 / 取网页）。\n\n同名目录已存在时跳过，不会覆盖你改过的东西。")
            .setPositiveButton("写入") { _, _ ->
                val n = PluginHost.writeSamples(ctx)
                Tips.short(ctx, if (n > 0) "已写入 $n 个示例" else "示例已存在，没有重复写入")
                if (n > 0) scan()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showHelp() {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle("插件怎么用")
            .setMessage(PluginHost.help(ctx))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun showPluginMenu(p: PluginHost.Plugin) {
        val ctx = context ?: return
        val acts = listOf("运行", if (p.enabled) "禁用" else "启用", "详情")
        MaterialAlertDialogBuilder(ctx)
            .setTitle(p.title())
            .setItems(acts.toTypedArray()) { _, w ->
                when (w) {
                    0 -> runPlugin(p)
                    1 -> {
                        PluginHost.setEnabled(ctx, p.id, !p.enabled)
                        scan()
                    }
                    else -> MaterialAlertDialogBuilder(ctx)
                        .setTitle(p.title())
                        .setMessage(
                            buildString {
                                append("id：").append(p.id).append('\n')
                                append("版本：").append(p.version.ifBlank { "未写" }).append('\n')
                                append("作者：").append(p.author.ifBlank { "未写" }).append('\n')
                                append("引擎：").append(p.engine).append('\n')
                                append("入口：").append(p.entry?.name ?: "找不到").append('\n')
                                append("权限：").append(
                                    if (p.permissions.isEmpty()) "无"
                                    else p.permissions.joinToString("、")
                                ).append('\n')
                                append("状态：").append(if (p.enabled) "已启用" else "已禁用").append('\n')
                                if (p.desc.isNotBlank()) append('\n').append(p.desc)
                            }
                        )
                        .setPositiveButton(R.string.ok, null)
                        .show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runPlugin(p: PluginHost.Plugin) {
        val ctx = context ?: return
        if (!p.enabled) {
            Tips.short(ctx, "这个插件已禁用")
            return
        }
        Tips.short(ctx, "正在执行…")
        val app = ctx.applicationContext
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            // 脚本可能连网或读写文件，也可能死循环。
            // 死循环由宿主的指令数上限截断，不会把界面卡死。
            val out = PluginHost.run(app, p)
            h.post {
                if (!isAdded) return@post
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("执行结果：${p.title()}")
                    .setMessage(out.take(4000))
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
        }.start()
    }
}
