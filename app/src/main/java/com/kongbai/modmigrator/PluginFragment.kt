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

/**
 * 插件页：装插件、跑插件、启停、卸载。
 *
 * 插件是**目录里的文件**（manifest.json + 脚本），不是独立 APK。
 * 扫描是读目录，很快；安装要解压，跑脚本可能连网或读写文件，都放后台。
 */
class PluginFragment : Fragment() {

    private val items = mutableListOf<PluginHost.Plugin>()
    private lateinit var adapter: PluginAdapter
    private lateinit var tvEmpty: TextView
    private val handler = Bg.ui
    private var offered = false

    private val openZip = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) doInstall(uri) }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_plugin, container, false)
        tvEmpty = v.findViewById(R.id.tvEmpty)
        val rv = v.findViewById<RecyclerView>(R.id.rvPlugins)
        adapter = PluginAdapter(items) { p -> showPluginMenu(p) }
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter
        v.findViewById<Button>(R.id.btnScan).safeClickUnit { scan() }
        v.findViewById<Button>(R.id.btnInstall).safeClickUnit { pickZip() }
        tvEmpty.safeClickUnit { showHelp() }
        scan()
        return v
    }

    private fun scan() {
        val ctx = requireContext().applicationContext
        Thread {
            val found = PluginHost.scan(ctx)
            handler.post {
                if (!isAdded) return@post
                items.clear()
                items.addAll(found)
                adapter.notifyDataSetChanged()
                tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                if (items.isEmpty()) {
                    tvEmpty.text = buildString {
                        append("还没有插件。点上面的「安装」选一个插件包，或点这里看说明。\n\n")
                        append("插件目录：").append(PluginHost.dir(ctx).absolutePath)
                    }
                    if (!offered) {
                        offered = true
                        offerSamples()
                    }
                }
            }
        }.start()
    }

    private fun pickZip() {
        runCatching {
            openZip.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }.onFailure { toast("打不开文件选择器：${it.message}") }
    }

    private fun doInstall(uri: android.net.Uri) {
        val ctx = context ?: return
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        toast("正在安装…")
        val app = ctx.applicationContext
        Thread {
            val (ok, msg) = PluginHost.installFromZip(app, uri)
            handler.post {
                if (!isAdded) return@post
                if (ok) {
                    toast(msg)
                    scan()
                } else {
                    SheetDialog.show(ctx, "安装失败", msg, listOf("知道了" to null))
                }
            }
        }.start()
    }

    private fun offerSamples() {
        val ctx = context ?: return
        if (!isAdded) return
        SheetDialog.show(
            ctx,
            "写入示例插件",
            "会在插件目录里写入三个能跑的示例（打招呼 / 列目录 / 取网页）。\n\n" +
                "同名目录已存在时跳过，不会覆盖你改过的东西。\n\n" +
                "跑通之后再照着改成自己的，比对着说明猜快。",
            listOf(
                "写入" to {
                    val n = PluginHost.writeSamples(ctx)
                    toast(if (n > 0) "已写入 $n 个示例" else "示例已存在，没有重复写入")
                    if (n > 0) scan()
                },
                getString(R.string.cancel) to null
            )
        )
    }

    private fun showHelp() {
        val ctx = context ?: return
        SheetDialog.show(ctx, "插件怎么用", PluginHost.help(ctx), listOf(getString(R.string.ok) to null))
    }

    private fun showPluginMenu(p: PluginHost.Plugin) {
        val ctx = context ?: return
        val acts = listOf("运行", if (p.enabled) "禁用" else "启用", "详情", "卸载")
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(p.title())
            .setItems(acts.toTypedArray()) { _, w ->
                when (w) {
                    0 -> runPlugin(p)
                    1 -> {
                        PluginHost.setEnabled(ctx, p.id, !p.enabled)
                        scan()
                    }
                    2 -> detail(p)
                    3 -> confirmUninstall(p)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun detail(p: PluginHost.Plugin) {
        val ctx = context ?: return
        SheetDialog.show(ctx, p.title(), buildString {
            append("id：").append(p.id).append('\n')
            append("版本：").append(p.version.ifBlank { "未写" }).append('\n')
            append("作者：").append(p.author.ifBlank { "未写" }).append('\n')
            append("引擎：").append(p.engine).append('\n')
            append("入口：").append(p.entry?.name ?: "找不到").append('\n')
            append("权限：").append(
                if (p.permissions.isEmpty()) "无" else p.permissions.joinToString("、")
            ).append('\n')
            append("状态：").append(if (p.enabled) "已启用" else "已禁用").append('\n')
            if (p.desc.isNotBlank()) append('\n').append(p.desc)
        }, listOf(getString(R.string.ok) to null))
    }

    private fun confirmUninstall(p: PluginHost.Plugin) {
        val ctx = context ?: return
        SheetDialog.show(
            ctx, "卸载 ${p.title()}",
            "会删掉整个插件目录，脚本和清单一起没，不可恢复。\n\n只是暂时不想用的话，点「禁用」就够了。",
            listOf(
                "卸载" to {
                    val ok = PluginHost.uninstall(ctx, p)
                    toast(if (ok) "已卸载" else "删不掉这个目录")
                    scan()
                },
                getString(R.string.cancel) to null
            )
        )
    }

    private fun runPlugin(p: PluginHost.Plugin) {
        val ctx = context ?: return
        if (!p.enabled) {
            toast("这个插件已禁用")
            return
        }
        toast("正在执行…")
        val app = ctx.applicationContext
        Thread {
            val out = PluginHost.run(app, p)
            handler.post {
                if (!isAdded) return@post
                SheetDialog.show(
                    ctx, "执行结果：${p.title()}", out.take(8000),
                    listOf(getString(R.string.ok) to null)
                )
            }
        }.start()
    }

    private fun toast(s: String) {
        handler.post { if (isAdded) Tips.short(context, s) }
    }
}
