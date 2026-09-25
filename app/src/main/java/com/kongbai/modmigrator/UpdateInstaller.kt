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

    /** 调起系统安装界面 */
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

            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
}
