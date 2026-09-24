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
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

/**
 * 整合包更新。
 *
 * 对应 PC 端 Prism / MultiMC 的「Mod updates」：
 * 逐个算出本地 jar 的 SHA-1，走 Modrinth 的 version_file 接口反查项目，
 * 再拉该项目在当前 MC 版本 + 加载器下的最新版本，比对出可更新项。
 * 更新是「下载到目标目录」，不会自动替换原文件，方便你先试再换。
 */
class ModpackFragment : Fragment() {

    private val exec = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var box: LinearLayout
    private lateinit var tvState: TextView
    private val results = mutableListOf<UpdateRow>()

    data class UpdateRow(
        var file: String,
        var current: String,
        var latest: String,
        var url: String,
        var latestName: String,
        var status: String
    )

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
        box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        tvState = TextView(ctx).apply {
            text = "点「检查更新」开始。需要先在迁移页选好「迁移前」的版本目录。"
            textSize = 13f
            setTextColor(resources.getColor(R.color.textSecondary, null))
        }
        root.addView(tvState)
        root.addView(mkButton("检查更新") { checkUpdates() })
        root.addView(mkButton("下载全部更新") { downloadAll() })
        root.addView(box)
        return scroll
    }

    private fun mkButton(text: String, act: () -> Unit): MaterialButton {
        val ctx = requireContext()
        return MaterialButton(
            ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            this.text = text
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (8 * resources.displayMetrics.density).toInt()
            layoutParams = lp
            setOnClickListener { act() }
        }
    }

    private fun toast(s: String) {
        handler.post {
            if (isAdded) Toast.makeText(context, s, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setState(s: String) {
        handler.post { if (isAdded) tvState.text = s }
    }

    private fun bg(block: () -> Unit) {
        exec.execute {
            try {
                block()
            } catch (t: Throwable) {
                setState("出错了：${t.message}")
            }
        }
    }

    private fun checkUpdates() {
        val ctx = requireContext()
        val p = Prefs.get(ctx)
        val src = p.getString(K.SRC_URI, null)
        if (src.isNullOrBlank()) {
            toast("请先在迁移页选择「迁移前」的版本目录")
            return
        }
        val mc = p.getString(K.DEF_VERSION, "") ?: ""
        val loader = p.getString(K.DEF_LOADER, "") ?: "auto"
        setState("正在算哈希并查询（离线模式下无法检查）…")
        if (p.getBoolean(K.OFFLINE, false)) {
            setState("离线模式：无法联网检查更新")
            return
        }
        bg {
            val root = Fs.tree(ctx, src)
            val mods = root?.let { Fs.find(it, "mods", 2) }
            if (mods == null) {
                setState("源目录里没有找到 mods 文件夹")
                return@bg
            }
            val jars = Fs.children(mods).filter {
                it.isFile && (it.name ?: "").endsWith(".jar", true)
            }
            val rows = ArrayList<UpdateRow>()
            var done = 0
            for (j in jars) {
                done++
                setState("检查中 $done/${jars.size}：${j.name}")
                val sha = try {
                    Fs.sha1(ctx, j)
                } catch (t: Throwable) {
                    ""
                }
                val hit = if (sha.isBlank()) null else ModrinthApi.lookupHashSimple(sha)
                if (hit == null) {
                    rows.add(
                        UpdateRow(
                            j.name ?: "", "", "", "", "",
                            if (sha.isBlank()) "哈希计算失败" else "Modrinth 上没找到"
                        )
                    )
                    continue
                }
                val pid = hit.first
                val cur = hit.second
                val vs = ModrinthApi.versions(pid, mc, loader)
                val f0 = vs.firstOrNull()
                if (f0 == null) {
                    rows.add(UpdateRow(j.name ?: "", cur, "", "", "", "当前版本下无可用更新"))
                    continue
                }
                val same = f0.fileName.equals(j.name, true)
                rows.add(
                    UpdateRow(
                        file = j.name ?: "",
                        current = cur,
                        latest = f0.fileName.substringBeforeLast(".jar"),
                        url = f0.url,
                        latestName = f0.fileName,
                        status = if (same) "已是最新" else "可更新"
                    )
                )
            }
            handler.post {
                if (!isAdded) return@post
                results.clear()
                results.addAll(rows)
                render()
                val n = rows.count { it.status == "可更新" }
                setState("共 ${rows.size} 个模组，其中 $n 个可更新")
            }
        }
    }

    private fun render() {
        if (!isAdded) return
        val ctx = requireContext()
        box.removeAllViews()
        for (r in results) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (8 * resources.displayMetrics.density).toInt()
                setPadding(0, pad, 0, pad)
            }
            val t = TextView(ctx).apply {
                text = "${r.file}\n${r.status}${if (r.latest.isNotBlank()) " → ${r.latest}" else ""}"
                textSize = 13f
                setTextIsSelectable(true)
            }
            row.addView(t)
            if (r.url.isNotBlank() && r.status == "可更新") {
                row.addView(MaterialButton(
                    ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    this.text = "下载新版"
                    setOnClickListener { downloadOne(r) }
                })
            }
            box.addView(row)
        }
    }

    private fun downloadOne(r: UpdateRow) {
        val ctx = context ?: return
        toast("开始下载 ${r.latestName}")
        exec.execute {
            try {
                val dir = WorkDir.modsDir(ctx) ?: Targets.modsDir(ctx)
                if (dir == null) {
                    toast("目标目录不可用，请先在设置里选工作目录")
                    return@execute
                }
                Downloader.download(ctx, r.url, dir, r.latestName, emptyMap())
                handler.post {
                    if (!isAdded) return@post
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle("下载完成")
                        .setMessage("${r.latestName}\n\n已放到目标 mods 目录，替换旧文件前建议先备份。")
                        .setPositiveButton(R.string.ok, null)
                        .show()
                }
            } catch (t: Throwable) {
                toast("下载失败：${t.message}")
            }
        }
    }

    private fun downloadAll() {
        val ctx = context ?: return
        val todo = results.filter { it.status == "可更新" && it.url.isNotBlank() }
        if (todo.isEmpty()) {
            toast("没有可更新的模组")
            return
        }
        toast("开始下载 ${todo.size} 个")
        exec.execute {
            var ok = 0
            for (r in todo) {
                try {
                    val dir = WorkDir.modsDir(ctx) ?: Targets.modsDir(ctx)
                    if (dir != null) {
                        Downloader.download(ctx, r.url, dir, r.latestName, emptyMap())
                        ok++
                    }
                } catch (t: Throwable) {
                }
            }
            handler.post {
                if (!isAdded) return@post
                setState("下载完成：$ok/${todo.size}")
                toast("完成 $ok/${todo.size}")
            }
        }
    }
}
