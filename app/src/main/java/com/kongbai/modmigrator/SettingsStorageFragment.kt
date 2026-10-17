package com.kongbai.modmigrator

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import kotlinx.coroutines.delay

/**
 * 设置 → 存储。
 *
 * 四块：工作目录、游戏目录、多个启动器各自的目录、云盘备份。
 *
 * 目录授权走 SAF，结果回到 Fragment 的 onActivityResult；
 * 界面用一个自增计数驱动重绘，不直接持有 View。
 */
class SettingsStorageFragment : Fragment() {

    private var gen by mutableIntStateOf(0)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        return ComposeView(ctx).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                McTheme(ctx) {
                    StorageScreen(ctx, gen) { code -> launchPick(code) }
                }
            }
        }
    }

    private fun launchPick(code: Int) {
        val act = requireActivity()
        when (code) {
            21 -> DirGuide.pick(act, 21, DirGuide.Purpose.FREE)
            22 -> DirGuide.pick(act, 22, DirGuide.Purpose.FREE)
            23 -> {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                i.addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                runCatching { startActivityForResult(i, 23) }
                    .onFailure { Tips.short(requireContext(), "打不开目录选择器") }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val ctx = requireContext()

        if (requestCode == 23) {
            val added = GameDirs.add(ctx, uri)
            gen++
            Tips.short(
                ctx,
                if (added) getString(R.string.cfg_gamedir_added, GameDirs.size(ctx))
                else getString(R.string.cfg_gamedir_exists)
            )
            return
        }

        if (requestCode == 22) {
            // SAF 给的是 tree URI，连同持久化授权一起保存，保证重启后仍可用
            val flags = data.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, flags) }
            val path = DirGuide.human(uri.toString())
            Prefs.get(ctx).edit()
                .putString(K.GAME_DIR, uri.toString())
                .putString(K.GAME_DIR_NAME, path)
                .apply()
            gen++
            Tips.long(ctx, "已保存游戏目录：$path")
            return
        }

        if (requestCode == 21) {
            // persist() 返回的是"是否真的拿到持久化授权"。
            // 忽略返回值一律弹"已设置"的话，take() 失败时 URI 照样存了，
            // 界面显示已授权、重启后却读不了，导出下载全落空。
            val ok = WorkDir.persist(ctx, uri)
            gen++
            Tips.long(
                ctx,
                if (ok) "工作目录已设置"
                else "目录已选中，但持久化授权未成功（重启后可能失效）"
            )
        }
    }
}

@Composable
private fun StorageScreen(ctx: android.content.Context, gen: Int, pick: (Int) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {

        // ---------- 工作目录 ----------
        McSectionTitle("工作目录")
        val workUri = remember(gen) { WorkDir.uri(ctx) }
        McNote(
            if (workUri.isBlank()) "未设置：所有导出与缓存会放在应用私有目录"
            else "工作目录：${DirGuide.human(workUri)}"
        )
        McButtonRow(
            "选择工作目录",
            { pick(21) },
            "清除",
            {
                Prefs.get(ctx).edit().putString(K.WORKDIR_URI, "").apply()
                WorkDir.invalidate()
                Tips.short(ctx, "已清除工作目录")
            }
        )

        // ---------- 游戏目录 ----------
        McSectionTitle(ctx.getString(R.string.cfg_gamedir_title))
        McNote(ctx.getString(R.string.cfg_gamedir_hint))
        val found = remember(gen) { LauncherDirs.detect(ctx) }
        val savedPath = remember(gen) { Prefs.get(ctx).getString(K.GAME_DIR, "") ?: "" }

        val labels = ArrayList<String>()
        val paths = ArrayList<String>()
        labels.add("（不指定，用默认位置）")
        paths.add("")
        if (savedPath.isNotBlank()) {
            val n = found.firstOrNull { it.second.absolutePath == savedPath }?.first?.name
                ?: "已保存的目录"
            labels.add("$n（当前）")
            paths.add(savedPath)
        }
        for ((e, f) in found) {
            if (f.absolutePath == savedPath) continue
            val ok = LauncherDirs.looksLikeMinecraft(f)
            labels.add(
                "${e.name}${if (ok) "" else "（空目录）"}" +
                    "${if (e.note.isNotBlank()) " · ${e.note}" else ""}"
            )
            paths.add(f.absolutePath)
        }
        val curIdx = paths.indexOf(savedPath)
        McSpinner(labels, if (curIdx >= 0) curIdx else 0, { i ->
            val p = paths.getOrNull(i) ?: return@McSpinner
            Prefs.get(ctx).edit().putString(K.GAME_DIR, p).apply()
            Tips.short(ctx, if (p.isBlank()) "已清除游戏目录" else "已保存：\n$p")
        }, "选一个已找到的目录")

        if (savedPath.isBlank()) {
            McNote(ctx.getString(R.string.cfg_gamedir_none))
        } else {
            val f = java.io.File(savedPath)
            McNote(
                "已保存：$savedPath" +
                    if (f.isDirectory) ""
                    else "\n⚠️ 该目录现在不存在，可能被删或启动器改了路径"
            )
        }
        McFilledButton(ctx.getString(R.string.cfg_gamedir_pick)) { pick(22) }

        // ---------- 认不出这个启动器 ----------
        McSectionTitle(ctx.getString(R.string.dir_unknown_title))
        McNote(ctx.getString(R.string.dir_unknown_hint))

        val sync = remember(gen) { PrivSync.status(ctx) }
        McNote(sync.text(ctx))

        val cands = remember(gen) { LauncherAccess.unknownCandidates(ctx) }
        var pickIdx by remember { mutableIntStateOf(0) }
        if (cands.isNotEmpty()) {
            McSpinner(
                listOf("（不指定，先自己选公开目录）") +
                    cands.map { "${it.first} · ${it.second}" },
                pickIdx, { pickIdx = it }, "疑似启动器"
            )
        }

        var privLog by remember { mutableStateOf("") }
        var privBusy by remember { mutableStateOf(false) }
        var privResult by remember { mutableStateOf<LauncherAccess.PackResult?>(null) }

        McFilledButton(ctx.getString(R.string.way_pick_public)) { pick(22) }

        McFilledButton(
            if (privBusy) "正在读取…" else ctx.getString(R.string.way_sync_private)
        ) {
            if (privBusy) return@McFilledButton
            if (!sync.ready) {
                if (sync.installed) PrivSync.requestPermission() else openSyncHome(ctx)
                return@McFilledButton
            }
            val entry = if (pickIdx > 0 && pickIdx - 1 < cands.size) {
                val (label, pkg) = cands[pickIdx - 1]
                LauncherAccess.fromPackage(pkg, label)
            } else LauncherAccess.unknown()
            val found = LauncherAccess.Found(
                entry, entry.privNew.firstOrNull().orEmpty(),
                LauncherAccess.Kind.PRIVATE, true
            )
            // 读目录 + 打包是重活，和迁移互斥：两个大任务一起往磁盘写会把 IO 打满
            if (!MigGuard.acquire(MigGuard.TAG_PRIV)) {
                Tips.short(ctx, ctx.getString(R.string.mig_guard_busy))
                return@McFilledButton
            }
            privBusy = true
            privLog = ""
            privResult = null
            Bg.io.execute {
                var res: LauncherAccess.PackResult? = null
                try {
                    res = LauncherAccess.packFromPrivate(ctx, found) { line ->
                        Bg.ui.post { privLog += line + "\n" }
                    }
                } catch (t: Throwable) {
                    Err.ignore(t, "同步器读取")
                    Bg.ui.post { privLog += "\n出错：${Err.humanMessage(t)}\n" }
                } finally {
                    // 放在 finally 里：抛异常时按钮不会永远停在「正在读取…」
                    MigGuard.release(MigGuard.TAG_PRIV)
                }
                val r = res
                Bg.ui.post {
                    privBusy = false
                    privResult = r
                    if (r?.ok == true) {
                        privLog += "\n" + ctx.getString(R.string.way_launcher_import_desc)
                    } else {
                        privLog += "\n读不出来：改用「自己选公开目录」，或让启动器自己导入"
                    }
                }
            }
        }

        // 打包成功后给两条出路：直接发给启动器导入，或自己去下载目录拿
        val pr = privResult
        if (pr?.ok == true) {
            McNote(
                ctx.getString(
                    R.string.priv_pack_meta,
                    pr.mcVersion.ifBlank { ctx.getString(R.string.priv_pack_unknown) },
                    pr.loader.ifBlank { ctx.getString(R.string.priv_pack_unknown) }
                )
            )
            McFilledButton(ctx.getString(R.string.priv_pack_share)) {
                LauncherAccess.sharePack(ctx, pr)
            }
            if (pr.savedTo.isNotBlank()) {
                McNote(ctx.getString(R.string.priv_pack_done, pr.savedTo))
            }
        }

        if (privLog.isNotBlank()) McNote(privLog.trim())

        // ---------- 多个启动器各自的目录 ----------
        McSectionTitle(ctx.getString(R.string.cfg_gamedir_multi_title))
        McNote(ctx.getString(R.string.cfg_gamedir_multi_hint))
        val items = remember(gen) { GameDirs.list(ctx) }
        val cur = remember(gen) { GameDirs.current(ctx) }
        if (items.isEmpty()) {
            McNote(ctx.getString(R.string.cfg_gamedir_empty))
        } else {
            for (e in items) {
                val isCur = cur != null && cur.uri == e.uri
                Row(
                    Modifier.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (isCur) "${e.label()}（${ctx.getString(R.string.cfg_gamedir_cur)}）"
                        else e.label(),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (!isCur) {
                        TextButton(onClick = {
                            GameDirs.setCurrent(ctx, e.uri)
                            Tips.short(ctx, "已切换到 ${e.label()}")
                        }) { Text(ctx.getString(R.string.cfg_gamedir_use), fontSize = 12.sp) }
                    }
                    TextButton(onClick = {
                        GameDirs.remove(ctx, e.uri)
                        Tips.short(ctx, "已移除 ${e.label()}")
                    }) { Text(ctx.getString(R.string.cfg_gamedir_del), fontSize = 12.sp) }
                }
            }
        }
        McFilledButton(ctx.getString(R.string.cfg_gamedir_add)) { pick(23) }

        // ---------- 云盘备份 ----------
        McSectionTitle("云盘备份")
        var url by remember(gen) { mutableStateOf(CloudBackup.uploadUrl(ctx)) }
        LaunchedEffect(url) { delay(400); CloudBackup.setUploadUrl(ctx, url) }

        McField(url, { url = it }, ctx.getString(R.string.cfg_cloud_url_hint))

        val intervalLabels = CloudBackup.INTERVALS.map { it.second }
        val ih = remember(gen) { CloudBackup.intervalHours(ctx) }
        val iIdx = CloudBackup.INTERVALS.indexOfFirst { it.first == ih }
        McSpinner(intervalLabels, if (iIdx >= 0) iIdx else 3, { i ->
            val h = CloudBackup.INTERVALS.getOrNull(i)?.first ?: 24
            CloudBackup.setIntervalHours(ctx, h)
        }, "自动备份间隔")

        var icon by remember(gen) { mutableStateOf(Prefs.get(ctx).getBoolean(K.LOCAL_ICON, true)) }
        McSwitchRow(
            icon,
            {
                icon = it
                Prefs.get(ctx).edit().putBoolean(K.LOCAL_ICON, it).apply()
            },
            ctx.getString(R.string.cfg_local_icon),
            ctx.getString(R.string.cfg_local_icon_hint)
        )

        McButtonRow(
            "打开云盘",
            {
                val u = CloudBackup.uploadUrl(ctx)
                if (u.isBlank()) Tips.short(ctx, "先填上传地址，或直接用内置浏览器打开你的云盘")
                else WebActivity.open(ctx, u, "云盘")
            },
            ctx.getString(R.string.cfg_backup_now),
            {
                // 先把输入框内容落盘再读：地址框原本只在失去焦点时保存，
                // 填完直接点「立即备份」焦点可能还没丢，配置里仍是空的，
                // 用户明明刚填完却弹"请先填写地址"。
                CloudBackup.setUploadUrl(ctx, url.trim())
                val u = CloudBackup.uploadUrl(ctx)
                if (u.isBlank()) {
                    Tips.short(ctx, "请先填写云盘上传地址")
                } else {
                    Tips.short(ctx, "正在备份…")
                    Bg.io.execute {
                        val msg = runCatching { SyncManager.upload(ctx) { } }
                            .getOrNull() ?: "备份出错"
                        val done = CloudBackup.ok(msg)
                        if (done) CloudBackup.markBackedUp(ctx)
                        Bg.ui.post {
                            Tips.long(ctx, if (done) "备份完成：$msg" else "备份失败：$msg")
                        }
                    }
                }
            }
        )
    }
}

/** 打开同步器下载页。用外部浏览器，不占用本应用的 WebView */
private fun openSyncHome(ctx: android.content.Context) {
    runCatching {
        ctx.startActivity(
            android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(PrivSync.HOME)
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { Tips.short(ctx, "打不开浏览器") }
}
