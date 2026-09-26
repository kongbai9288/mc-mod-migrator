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
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
            // ⚠️ 两个问题：
            //  ① `Store.clear` + 遍历删除 cacheDir 都是**磁盘 IO**，
            //     却在**主线程**跑 —— 缓存多了会明显卡顿。
            //  ② **完全没有反馈**：点完按钮什么提示都没有，
            //     用户不知道清没清掉，只会以为这个按钮是坏的。
            val ctx = requireContext()
            Toast.makeText(ctx, "正在清理…", Toast.LENGTH_SHORT).show()
            exec.execute {
                var ok = true
                try {
                    Store.clear(ctx)
                    val c = ctx.cacheDir
                    if (c.exists()) c.listFiles()?.forEach { it.deleteRecursively() }
                } catch (t: Throwable) {
                    Err.ignore(t, "清理缓存")
                    ok = false
                }
                handler.post {
                    if (!isAdded) return@post
                    Toast.makeText(
                        ctx,
                        if (ok) "已清理标记链接与缓存" else "清理未全部完成，可稍后重试",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
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

        fillAbout(v)
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
                                    // 走系统 DownloadManager：断点续传、通知栏进度、
                                    // 完成后调起安装都是系统自带的，比开 WebView 稳
                                    UpdateInstaller.download(ctx, u, rel.tag)
                                } catch (t: Throwable) {
                                    Toast.makeText(ctx, "无法开始下载：${t.message}", Toast.LENGTH_SHORT).show()
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
        // ⚠️ `Perms.describe()` 内部会对每个已保存的目录调 `usable()`
        // 去 ContentResolver 查可用性 —— 是 IPC/IO，之前在**主线程**跑。
        // 而且清理时 `Perms.granted(ctx)` 被调了**两次**
        // （一次 count、一次循环），每次都重新拉一遍系统授权列表，
        // 中间还对每个 URI 各查一次可用性。
        Toast.makeText(ctx, "正在检查…", Toast.LENGTH_SHORT).show()
        exec.execute {
            val list = Perms.describe(ctx)
            handler.post {
                if (!isAdded) return@post
                val sb = StringBuilder()
                for ((k, v) in list) {
                    sb.append("$k：$v\n")
                }
                if (list.isEmpty()) sb.append("（还没有授权任何目录）\n")
                sb.append("\n如果某个目录显示「已失效」，到对应页面重新选一次即可。")
                sb.append("\n授权占用接近上限时会自动回收不再使用的旧授权。")
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("授权状态")
                    .setMessage(sb.toString())
                    .setPositiveButton(R.string.ok, null)
                    .setNeutralButton("清理失效授权") { _, _ ->
                        exec.execute {
                            val all = Perms.granted(ctx)
                            val dead = all.filter { !Perms.usable(ctx, it.uri.toString()) }
                            for (up in dead) Perms.release(ctx, up.uri.toString())
                            handler.post {
                                if (!isAdded) return@post
                                Toast.makeText(
                                    ctx, "已清理 ${dead.size} 条失效授权", Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                    .show()
            }
        }
    }


    /**
     * 底部那行版本说明。
     *
     * ⚠️ 之前 strings.xml 里把版本号**写死**成了 "v1.0.0"，
     * 于是 App 已经是 2.0.1 了，设置页最底下还写着 v1.0.0 ——
     * 每次发版都得记得手动改，忘了就一直错。
     * 现在改成运行时从 PackageInfo 里取真实版本，永远不会和安装包不一致。
     */
    protected fun fillAbout(v: View) {
        val tv = v.findViewById<android.widget.TextView>(R.id.tvAbout) ?: return
        val ctx = v.context
        val ver = try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            pi.versionName ?: ""
        } catch (t: Throwable) { "" }
        tv.text = if (ver.isBlank()) getString(R.string.about)
        else "模组迁移助手 v$ver · 开源自用工具"
    }

    private fun openInfo(file: String, title: String) {
        val i = Intent(requireContext(), InfoActivity::class.java)
        i.putExtra("file", file)
        i.putExtra("title", title)
        startActivity(i)
    }

    /**
     * 崩溃日志。
     *
     * 安卓剪贴板对长文本支持很差（多数 ROM 直接截断），
     * 所以不走复制，而是：先**写成 .log 文件**，再调系统分享面板发出去。
     */
    private fun showCrash() {
        val ctx = requireContext()
        // ⚠️ `CrashHandler.readAll` 是**读磁盘文件**（可能好几个 .log），
        // 之前在**主线程**调 —— 日志大时点一下就卡住。
        // 另外这里用的是系统 `AlertDialog.Builder`，不认 Material 的
        // materialAlertDialogTheme，按钮和标题不跟主题色，
        // 看起来像另一个应用弹出来的（CrashReport 那次已改，这里漏了）。
        Toast.makeText(ctx, "正在读取…", Toast.LENGTH_SHORT).show()
        exec.execute {
            val txt = CrashHandler.readAll(ctx)
            handler.post {
                if (!isAdded) return@post
                if (txt.isBlank()) {
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle(R.string.cfg_crash_log)
                        .setMessage("暂无崩溃记录")
                        .setPositiveButton(R.string.ok, null)
                        .show()
                    return@post
                }
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.cfg_crash_log)
                    .setMessage(txt.take(4000))
                    .setPositiveButton(R.string.ok, null)
                    .setNeutralButton("保存") { _, _ ->
                        CrashShare.save(ctx, txt)
                    }
                    .setNegativeButton("分享") { _, _ ->
                        CrashShare.share(ctx, txt, "ModMigrator 崩溃日志")
                    }
                    .show()
            }
        }
    }
    override fun onDestroyView() {
        super.onDestroyView()
        // 清理 Handler：页面销毁后若还有未执行的 post，
        // 回调里访问已销毁的 View 会直接崩。
        runCatching { handler.removeCallbacksAndMessages(null) }
    }

}
