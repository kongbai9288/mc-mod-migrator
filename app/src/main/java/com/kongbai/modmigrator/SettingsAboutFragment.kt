package com.kongbai.modmigrator

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 关于 / 更新 / 授权状态。
 *
 * 渲染改成 Compose，业务逻辑（UpdateChecker / Perms / CrashHandler）原样保留。
 *
 * 长按「第三方开源许可」进入开发者模式：正常流程里很多接口碰不到
 * （后端连不上就走不到登录、没有存档就走不到区域解析），出问题时没法复现。
 * 放长按是为了不打扰普通用户，也不占界面位置。
 */
class SettingsAboutFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        return ComposeView(ctx).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent { McTheme(ctx) { AboutScreen(ctx) } }
        }
    }
}

@Composable
private fun AboutScreen(ctx: android.content.Context) {
    var dialog by remember { mutableStateOf<AboutDlg?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        McSectionTitle("说明与许可")
        McButtonRow(
            ctx.getString(R.string.privacy_title),
            { openInfo(ctx, "privacy.txt", ctx.getString(R.string.privacy_title)) },
            ctx.getString(R.string.license_title),
            { openInfo(ctx, "licenses.txt", ctx.getString(R.string.license_title)) }
        )
        // 进过开发者模式的人清楚自己在做什么，
        // 后面的"已知局限性"确认框就不必再打扰他了
        McFilledButton("开发者模式") {
            runCatching { Prefs.get(ctx).edit().putBoolean(K.DEV_MODE, true).apply() }
            runCatching { ctx.startActivity(Intent(ctx, DevLabActivity::class.java)) }
            Tips.short(ctx, "开发者模式")
        }

        McSectionTitle("版本与更新")
        McNote(versionSummary(ctx))
        McButtonRow(
            "检查更新",
            { checkUpdate(ctx) { dialog = it } },
            "更新内容",
            { dialog = AboutDlg.Info("更新内容", changelog(ctx)) }
        )

        McSectionTitle("诊断")
        McButtonRow(
            ctx.getString(R.string.cfg_crash_log),
            { showCrash(ctx) { dialog = it } },
            "授权状态",
            { showPerms(ctx) { dialog = it } }
        )
        McFilledButton("清理缓存") { clearCache(ctx) }
    }

    when (val d = dialog) {
        is AboutDlg.Info -> McActionDialog(d.title, { dialog = null }, emptyList()) {
            McDlgText(d.body)
        }
        is AboutDlg.Update -> McActionDialog(d.title, { dialog = null }, d.actions) {
            McDlgText(d.body)
        }
        is AboutDlg.Crash -> McActionDialog("崩溃日志", { dialog = null }, d.actions) {
            McDlgText(d.body)
        }
        is AboutDlg.Perms -> McActionDialog("授权状态", { dialog = null }, d.actions) {
            McDlgText(d.body)
        }
        null -> Unit
    }
}

@Composable
private fun McDlgText(body: String) {
    Text(
        body,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth()
    )
}

private sealed class AboutDlg {
    data class Info(val title: String, val body: String) : AboutDlg()
    data class Update(
        val title: String, val body: String, val actions: List<Pair<String, () -> Unit>>
    ) : AboutDlg()
    data class Crash(
        val body: String, val actions: List<Pair<String, () -> Unit>>
    ) : AboutDlg()
    data class Perms(
        val body: String, val actions: List<Pair<String, () -> Unit>>
    ) : AboutDlg()
}

private fun openInfo(ctx: android.content.Context, file: String, title: String) {
    val i = Intent(ctx, InfoActivity::class.java)
    i.putExtra("file", file)
    i.putExtra("title", title)
    ctx.startActivity(i)
}

/** 版本号 + 构建号。运行时取 PackageInfo，不会和安装包不一致 */
private fun versionSummary(ctx: android.content.Context): String {
    return try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val ver = pi.versionName ?: "未知"
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) {
            pi.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pi.versionCode.toLong()
        }
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        "版本 $ver（构建 $code）\n首次安装：${sdf.format(Date(pi.firstInstallTime))}"
    } catch (t: Throwable) {
        "版本信息获取失败"
    }
}

private fun changelog(ctx: android.content.Context): String {
    val p = Prefs.get(ctx)
    val tag = p.getString(K.LAST_UPDATE_TAG, "") ?: ""
    val notes = p.getString(K.LAST_UPDATE_NOTES, "") ?: ""
    val at = p.getLong(K.LAST_UPDATE_AT, 0L)
    val whenTxt = if (at > 0) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(at))
    } else "尚未检查过"
    return if (tag.isBlank()) {
        "还没有检查过更新。点上面的「检查更新」试试。"
    } else {
        "最新标签：$tag\n检查时间：$whenTxt\n\n${notes.ifBlank { "（该版本没有填写更新说明）" }}"
    }
}

/** 手动检查更新：立刻出结果，不用等定时 */
private fun checkUpdate(ctx: android.content.Context, out: (AboutDlg) -> Unit) {
    Tips.short(ctx, "正在检查…")
    Bg.io.execute {
        val rel = runCatching { UpdateChecker.latest(ctx) }.getOrNull()
        Bg.ui.post {
            if (rel == null) {
                out(
                    AboutDlg.Info(
                        "检查更新",
                        "没能取到版本信息。\n\n常见原因：网络不通、GitHub 被墙、" +
                            "仓库地址没填或填错。可在设置 → 后端里确认仓库地址。"
                    )
                )
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
                sb.append("文件大小：")
                    .append(String.format(Locale.ROOT, "%.1fMB", rel.size / 1024.0 / 1024.0))
                    .append('\n')
            }
            sb.append('\n')
            if (!UpdateChecker.isVersionTag(rel.tag)) {
                sb.append("注意：最新发布的标签是「${rel.tag}」，不是标准版本号，")
                    .append("无法判断是否更新。请让仓库用 v1.0.1 这类语义化标签发布。\n\n")
            }
            sb.append(if (newer) "有新版本可用。\n\n" else "已是最新。\n\n")
            if (rel.notes.isNotBlank()) sb.append("更新内容：\n").append(rel.notes)

            val acts = ArrayList<Pair<String, () -> Unit>>()
            if (newer && rel.apkUrl.isNotBlank()) {
                acts += "去下载" to {
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
                        Tips.short(ctx, "无法开始下载：${t.message}")
                    }
                }
            }
            dialog(
                AboutDlg.Update(if (newer) "发现新版本 ${rel.tag}" else "检查更新", sb.toString(), acts)
            )
        }
    }
}

/**
 * 崩溃日志。
 *
 * 安卓剪贴板对长文本支持很差（多数 ROM 直接截断），
 * 所以不走复制，而是写成 .log 文件再调系统分享面板。
 */
private fun showCrash(ctx: android.content.Context, out: (AboutDlg) -> Unit) {
    Tips.short(ctx, "正在读取…")
    Bg.io.execute {
        val txt = CrashHandler.readAll(ctx)
        Bg.ui.post {
            if (txt.isBlank()) {
                out(AboutDlg.Info(ctx.getString(R.string.cfg_crash_log), "暂无崩溃记录"))
                return@post
            }
            out(
                AboutDlg.Crash(
                    txt.take(4000),
                    listOf(
                        "保存" to { CrashShare.save(ctx, txt) },
                        "分享" to { CrashShare.share(ctx, txt, "ModMigrator 崩溃日志") }
                    )
                )
            )
        }
    }
}

/**
 * 授权状态一览。
 *
 * `Perms.describe()` 内部会对每个已保存目录调 `usable()`
 * 去 ContentResolver 查可用性 —— 是 IPC/IO，必须放后台。
 */
private fun showPerms(ctx: android.content.Context, out: (AboutDlg) -> Unit) {
    Tips.short(ctx, "正在检查…")
    Bg.io.execute {
        val list = Perms.describe(ctx)
        Bg.ui.post {
            val sb = StringBuilder()
            for ((k, v) in list) sb.append("$k：$v\n")
            if (list.isEmpty()) sb.append("（还没有授权任何目录）\n")
            sb.append("\n如果某个目录显示「已失效」，到对应页面重新选一次即可。")
            sb.append("\n授权占用接近上限时会自动回收不再使用的旧授权。")
            out(
                AboutDlg.Perms(
                    sb.toString(),
                    listOf(
                        "清理失效授权" to {
                            Bg.io.execute {
                                val all = Perms.granted(ctx)
                                val dead = all.filter { !Perms.usable(ctx, it.uri.toString()) }
                                for (up in dead) Perms.release(ctx, up.uri.toString())
                                Bg.ui.post { Tips.short(ctx, "已清理 ${dead.size} 条失效授权") }
                            }
                        }
                    )
                )
            )
        }
    }
}

/** 清缓存：Store.clear + 删 cacheDir 都是磁盘 IO，必须放后台 */
private fun clearCache(ctx: android.content.Context) {
    Tips.short(ctx, "正在清理…")
    Bg.io.execute {
        var ok = true
        try {
            Store.clear(ctx)
            val c = ctx.cacheDir
            if (c.exists()) c.listFiles()?.forEach { it.deleteRecursively() }
        } catch (t: Throwable) {
            Err.ignore(t, "清理缓存")
            ok = false
        }
        Bg.ui.post {
            Tips.short(ctx, if (ok) "已清理标记链接与缓存" else "清理未全部完成，可稍后重试")
        }
    }
}
