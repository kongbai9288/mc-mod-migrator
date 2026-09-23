package com.kongbai.modmigrator

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class SettingsAboutFragment : Fragment() {

    private val exec = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_settings_about, container, false)
        v.findViewById<Button>(R.id.btnPrivacy).setOnClickListener {
            openInfo("privacy.txt", getString(R.string.privacy_title))
        }
        v.findViewById<Button>(R.id.btnLicenses).setOnClickListener {
            openInfo("licenses.txt", getString(R.string.license_title))
        }
        v.findViewById<Button>(R.id.btnCrash).setOnClickListener { showCrash() }
        v.findViewById<Button>(R.id.btnClear).setOnClickListener {
            Store.clear(requireContext())
            val c = requireContext().cacheDir
            if (c.exists()) c.listFiles()?.forEach { it.deleteRecursively() }
        }

        // 版本信息与更新
        val tvVersion = v.findViewById<TextView>(R.id.tvVersion)
        if (tvVersion != null) {
            tvVersion.text = versionSummary()
        }
        v.findViewById<Button>(R.id.btnCheckUpdate)?.setOnClickListener { checkUpdate() }
        v.findViewById<Button>(R.id.btnChangelog)?.setOnClickListener { showChangelog() }

        // 授权状态
        v.findViewById<Button>(R.id.btnPerms)?.setOnClickListener { showPerms() }

        return v
    }

    /** 版本号 + 构建号 */
    private fun versionSummary(): String {
        val ctx = requireContext()
        return try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val ver = pi.versionName ?: "未知"
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) {
                pi.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pi.versionCode.toLong()
            }
            val inst = pi.firstInstallTime
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
            "版本 $ver（构建 $code）\n首次安装：${sdf.format(Date(inst))}"
        } catch (t: Throwable) {
            "版本信息获取失败"
        }
    }

    /** 手动检查更新：立刻出结果，不用等定时 */
    private fun checkUpdate() {
        val ctx = requireContext()
        Toast.makeText(ctx, "正在检查…", Toast.LENGTH_SHORT).show()
        exec.execute {
            val rel = try {
                UpdateChecker.latest(ctx)
            } catch (t: Throwable) {
                null
            }
            handler.post {
                if (!isAdded) return@post
                if (rel == null) {
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle("检查更新")
                        .setMessage("没能取到版本信息。\n\n常见原因：网络不通、GitHub 被墙、仓库地址没填或填错。可在设置 → 后端里确认仓库地址。")
                        .setPositiveButton(R.string.ok, null)
                        .show()
                    return@post
                }
                val cur = UpdateChecker.local(ctx)
                val newer = UpdateChecker.isNewer(rel.tag, cur)
                val sb = StringBuilder()
                sb.append("当前版本：").append(cur).append('\n')
                sb.append("最新版本：").append(rel.tag).append('\n')
                if (rel.published.isNotBlank()) {
                    sb.append("发布时间：").append(rel.published.take(10)).append('\n')
                }
                if (rel.size > 0) {
                    sb.append("文件大小：").append(String.format(Locale.ROOT, "%.1fMB", rel.size / 1024.0 / 1024.0)).append('\n')
                }
                sb.append('\n')
                if (!UpdateChecker.isVersionTag(rel.tag)) {
                    sb.append("注意：最新发布的标签是「${rel.tag}」，不是标准版本号，无法判断是否更新。请让仓库用 v1.0.1 这类语义化标签发布。\n\n")
                }
                if (newer) {
                    sb.append("有新版本可用。\n\n")
                } else {
                    sb.append("已是最新。\n\n")
                }
                if (rel.notes.isNotBlank()) {
                    sb.append("更新内容：\n").append(rel.notes)
                }
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(if (newer) "发现新版本 ${rel.tag}" else "检查更新")
                    .setMessage(sb.toString())
                    .setPositiveButton(R.string.ok, null)
                    .apply {
                        if (newer && rel.apkUrl.isNotBlank()) {
                            setNeutralButton("去下载") { _, _ ->
                                try {
                                    val u = UpdateChecker.pickMirror(
                                        UpdateChecker.owner(ctx).ifBlank { UpdateChecker.defaultOwner() },
                                        UpdateChecker.repo(ctx).ifBlank { UpdateChecker.defaultRepo() },
                                        rel.tag,
                                        rel.apkUrl.substringAfterLast('/')
                                    )
                                    startActivity(
                                        Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u))
                                    )
                                } catch (t: Throwable) {
                                    Toast.makeText(ctx, "打不开下载页", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    .show()
            }
        }
    }

    /** 显示最近一次检查到的更新内容 */
    private fun showChangelog() {
        val ctx = requireContext()
        val p = Prefs.get(ctx)
        val tag = p.getString(K.LAST_UPDATE_TAG, "") ?: ""
        val notes = p.getString(K.LAST_UPDATE_NOTES, "") ?: ""
        val at = p.getLong(K.LAST_UPDATE_AT, 0L)
        val whenTxt = if (at > 0) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(at))
        } else {
            "尚未检查过"
        }
        val txt = if (tag.isBlank()) {
            "还没有检查过更新。点上面的「检查更新」试试。"
        } else {
            "最新标签：$tag\n检查时间：$whenTxt\n\n${notes.ifBlank { "（该版本没有填写更新说明）" }}"
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("更新内容")
            .setMessage(txt)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    /** 授权状态一览 */
    private fun showPerms() {
        val ctx = requireContext()
        val list = Perms.describe(ctx)
        val sb = StringBuilder()
        for ((k, v) in list) {
            sb.append("$k：$v\n")
        }
        sb.append("\n如果某个目录显示「已失效」，到对应页面重新选一次即可。")
        sb.append("\n授权占用接近上限时会自动回收不再使用的旧授权。")
        MaterialAlertDialogBuilder(ctx)
            .setTitle("授权状态")
            .setMessage(sb.toString())
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton("清理失效授权") { _, _ ->
                val n = Perms.granted(ctx).count { !Perms.usable(ctx, it.uri.toString()) }
                for (up in Perms.granted(ctx)) {
                    if (!Perms.usable(ctx, up.uri.toString())) {
                        Perms.release(ctx, up.uri.toString())
                    }
                }
                Toast.makeText(ctx, "已清理 $n 条失效授权", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun openInfo(file: String, title: String) {
        val i = Intent(requireContext(), InfoActivity::class.java)
        i.putExtra("file", file)
        i.putExtra("title", title)
        startActivity(i)
    }

    private fun showCrash() {
        val f = File(requireContext().filesDir, "crash.log")
        val txt = if (f.exists()) f.readText().take(6000) else "暂无崩溃记录"
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.cfg_crash_log)
            .setMessage(txt)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton("清空") { _, _ -> f.delete() }
            .show()
    }
}
