package com.kongbai.modmigrator

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * 应用更新下载与安装。
 *
 * 用**系统 DownloadManager**（Android 内置组件，无需第三方依赖、无许可证问题）：
 *   - 断点续传、通知栏进度、后台下载都是系统自带的，比自己实现稳
 *   - 下载到公共 Download 目录，完成后调起系统安装界面
 *
 * 之前这里是"打开一个 WebView 去访问 APK 直链"——
 * 那条路既没必要又容易在 WebView 初始化时崩，是"点去更新就崩"的直接原因。
 */
object UpdateInstaller {

    /** 当前正在下载的 id */
    private var lastId = -1L

    fun download(ctx: Context, url: String, version: String) {
        if (url.isBlank()) {
            Toast.makeText(ctx, "没有下载地址", Toast.LENGTH_SHORT).show()
            return
        }
        val name = "ModMigrator-$version.apk"
        try {
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("ModMigrator $version")
                setDescription("下载完成后点击安装")
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                setMimeType("application/vnd.android.package-archive")
                // 允许在移动网络下也继续（用户主动点的更新）
                setAllowedOverMetered(true)
                setAllowedOverRoaming(false)
            }
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            if (dm == null) {
                Toast.makeText(ctx, "系统下载服务不可用", Toast.LENGTH_SHORT).show()
                return
            }
            // 删掉同名旧文件，避免下载时被自动改名成 -1.apk
            runCatching {
                val old = File(
                    Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS
                    ), name
                )
                if (old.exists()) old.delete()
            }
            lastId = dm.enqueue(req)
            Toast.makeText(ctx, "已在通知栏开始下载，完成后点击安装", Toast.LENGTH_LONG).show()

            // 下载完成广播：直接调起安装
            registerReceiver(ctx)
        } catch (t: Throwable) {
            // DownloadManager 不可用时退回浏览器下载
            Toast.makeText(ctx, "无法使用系统下载，改用浏览器：$name", Toast.LENGTH_LONG).show()
            runCatching {
                WebActivity.open(ctx, url, "下载 $version")
            }
        }
    }

    private var receiver: BroadcastReceiver? = null

    /** 监听下载完成。用 applicationContext 注册，避免持有 Activity */
    private fun registerReceiver(ctx: Context) {
        try {
            if (receiver != null) return
            val app = ctx.applicationContext
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    if (id == null || id != lastId) return
                    try {
                        // id 为空就跳过，不能 !! 崩掉
                        val pid = id
                        if (pid != null) install(c ?: app, pid)
                    } catch (t: Throwable) { Err.ignore(t, "install(c ?: app, id!!)") }
                }
            }
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                app.registerReceiver(r, filter)
            }
            receiver = r
        } catch (t: Throwable) { Err.ignore(t, "receiver = r") }
    }

    /**
     * 调起系统安装界面。
     *
     * 两个必须同时满足的条件，缺一个都会"下载完了装不上"：
     *  1. Android 8.0（API 26）起要 `REQUEST_INSTALL_PACKAGES` 权限
     *     **且用户手动打开**"允许来自此来源的应用"开关（下面会引导去开）。
     *  2. Android 7.0（API 24）起给出去的 URI 必须是 `content://`，
     *     `file://` 会直接 FileUriExposedException。
     */
    private fun install(ctx: Context, id: Long) {
        try {
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                ?: return
            val uri = dm.getUriForDownloadedFile(id) ?: return

            // ── Android 8.0+ 还必须**用户手动授权**"允许来自此来源的应用" ──
            // 光在 Manifest 里声明权限不够：系统默认关闭这个开关，
            // 没开就 startActivity 会被直接拒绝，用户只看到"点了没反应"。
            // 这里先检查，没授权就跳到对应的设置页让用户打开。
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                val pm = ctx.packageManager
                val allowed = try {
                    pm.canRequestPackageInstalls()
                } catch (t: Throwable) {
                    true   // 拿不到状态就照常尝试
                }
                if (!allowed) {
                    Toast.makeText(
                        ctx,
                        "需要先允许「来自此来源的应用」，正在打开设置…",
                        Toast.LENGTH_LONG
                    ).show()
                    runCatching {
                        val i = Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${ctx.packageName}")
                        )
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(i)
                    }
                    return
                }
            }

            // ⚠️ Android 7.0（API 24）起，把 URI 传给其它应用**只认 content://**，
            // 传 `file://` 会直接抛 FileUriExposedException。
            // 而 `dm.getUriForDownloadedFile(id)` 并不保证返回 content:// ——
            // 下载到公共目录时它完全可能给出 `file://`
            // （取决于该文件有没有被 MediaProvider 收录）。
            // 一旦是 file://，到这一步就会崩，
            // 表现为"下载完了，点安装直接闪退/没反应"。
            // 这里按 scheme 判断，是 file 就用已配置好的 FileProvider 转一次。
            val target = if (uri.scheme == "file") {
                val p = uri.path ?: return
                FileProvider.getUriForFile(
                    ctx, "${ctx.packageName}.fileprovider", File(p)
                )
            } else uri

            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(target, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // 没有能处理安装的应用时别硬跳（某些精简 ROM 会直接抛 ActivityNotFound）
            if (i.resolveActivity(ctx.packageManager) == null) {
                Toast.makeText(ctx, "没有找到可处理安装的应用", Toast.LENGTH_LONG).show()
                return
            }
            ctx.startActivity(i)
        } catch (t: Throwable) {
            // 某些 ROM 不允许直接安装，退回让用户自己点通知
            Err.ignore(t, "调起安装界面")
            Toast.makeText(ctx, "请在通知栏点击已下载的安装包", Toast.LENGTH_LONG).show()
        }
    }

    /** 备用：用内置浏览器打开下载页 */
    fun openPage(ctx: Context, url: String, version: String) {
        WebActivity.open(ctx, url, "下载 $version")
    }

    /**
     * 补捡已经下载完、但没能自动调起安装的更新包。
     *
     * ## 为什么需要这个
     *
     * 下载完成靠的是**动态注册的 BroadcastReceiver**。
     * 而动态 Receiver 的生命周期跟进程绑定：用户点了更新后切到别的应用，
     * 系统为了省内存把我们的进程回收掉，Receiver 就没了。
     * 等 APK 下载完，广播发出去没人接 ——
     * 用户回来看到通知栏"下载完成"，点了却没反应，
     * 或者干脆不知道要去看通知栏，以为更新失败了。
     *
     * DownloadManager 自己是系统服务，下载**不会**因为我们的进程被杀而中断，
     * 它那边照样把状态记成"已完成"。所以只要回来时主动查一次，
     * 就能把漏掉的那次安装补上。
     *
     * 在 [MainActivity.onResume] 里调用即可。
     */
    fun checkPendingInstallations(ctx: Context) {
        try {
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                ?: return
            val q = DownloadManager.Query().setFilterByStatus(
                DownloadManager.STATUS_SUCCESSFUL
            )
            val ids = mutableListOf<Long>()
            dm.query(q)?.use { cur ->
                val idIdx = cur.getColumnIndex(DownloadManager.COLUMN_ID)
                val titleIdx = cur.getColumnIndex(DownloadManager.COLUMN_TITLE)
                val uriIdx = cur.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                if (idIdx < 0) return@use
                while (cur.moveToNext()) {
                    val id = cur.getLong(idIdx)
                    // 只挑我们自己下的更新包，别把用户别的下载也拉起来安装
                    val title = if (titleIdx >= 0) cur.getString(titleIdx) ?: "" else ""
                    val uri = if (uriIdx >= 0) cur.getString(uriIdx) ?: "" else ""
                    val mine = title.startsWith("ModMigrator") ||
                        uri.contains("ModMigrator", true)
                    if (mine) ids.add(id)
                }
            }
            // 只补捡最近一个，避免一次弹出多个安装界面
            val id = ids.maxOrNull() ?: return
            // 已经处理过就不再重复弹
            if (id == handledId) return
            handledId = id
            val prefs = ctx.getSharedPreferences("update_install", Context.MODE_PRIVATE)
            if (prefs.getLong("handled", -1L) == id) return
            prefs.edit().putLong("handled", id).apply()
            install(ctx, id)
        } catch (t: Throwable) {
            Err.ignore(t, "补捡待安装的更新包")
        }
    }

    /** 本次进程内已处理过的下载 id，防止重复弹安装 */
    private var handledId = -1L
}
