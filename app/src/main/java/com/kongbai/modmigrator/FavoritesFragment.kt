package com.kongbai.modmigrator

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment

/**
 * 收藏夹。
 *
 * 数据层（Favorites）与安装逻辑原样保留，只把渲染换成 Compose。
 *
 * 之前收藏只能「长按」触发、且没有统一查看的地方，表现就是"收藏没法用"。
 * 现在这里可以统一查看、安装、移除（移除走卡片长按）。
 */
class FavoritesFragment : Fragment() {

    /** 由 Compose 侧登记，回到前台时刷新列表 */
    private var resumeCb: (() -> Unit)? = null

    override fun onResume() {
        super.onResume()
        resumeCb?.invoke()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        return ComposeView(ctx).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent { McTheme(ctx) { FavoritesScreen(ctx) { resumeCb = it } } }
        }
    }
}

@Composable
private fun FavoritesScreen(ctx: android.content.Context, register: (() -> Unit) -> Unit) {
    var list by remember { mutableStateOf(Favorites.list(ctx)) }
    var pendingRemove by remember { mutableStateOf<MarketMod?>(null) }

    ResumeEffect(register) { list = Favorites.list(ctx) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        McNote("长按卡片可以移除收藏。点「安装」会装到目标 mods 目录。")
        if (list.isEmpty()) {
            McEmpty(
                "还没有收藏",
                "在市场里点模组卡片上的「收藏」就会存在这里。\n" +
                    "收藏不需要先设置工作目录，会直接存在应用内。"
            )
        } else {
            Text("共 ${list.size} 个", Modifier.padding(bottom = 8.dp), fontSize = 12.sp)
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                itemsIndexed(list, key = { i, _ -> i }) { _, m ->
                    McModCard(
                        iconUrl = m.iconUrl,
                        title = m.name,
                        summary = m.summary.take(60).ifBlank { "来自 ${m.source}" },
                        action = "安装",
                        onAction = { install(ctx, m) },
                        onLongClick = { pendingRemove = m }
                    )
                }
            }
        }
    }

    pendingRemove?.let { m ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("移除收藏") },
            text = { Text("确定不再收藏「${m.name}」？") },
            confirmButton = {
                TextButton(onClick = {
                    Favorites.remove(ctx, m)
                    list = Favorites.list(ctx)
                    pendingRemove = null
                    Tips.short(ctx, "已移除")
                }) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("取消") }
            }
        )
    }
}

/**
 * 安装。
 *
 * ⚠️ 之前这里把 MC 版本和加载器都传空串，等于不加任何过滤，
 * 拿到的是这个项目所有游戏版本里最新发布的那一个 ——
 * 你在 1.20.1，它可能返回 1.21.x 的构建，装进去进游戏直接报错。
 * 所以这里必须带上默认版本和加载器。
 */
private fun install(ctx: android.content.Context, m: MarketMod) {
    val p = Prefs.get(ctx)
    val mc = p.getString(K.DEF_VERSION, "") ?: ""
    val ld = p.getString(K.DEF_LOADER, "auto") ?: "auto"
    Tips.short(ctx, "开始解析下载地址…")
    Thread {
        try {
            val pair: Pair<String, String>? = if (m.source == "curseforge") {
                // CF 的下载要先过读秒，直连 CDN 下到的常常只是一个 HTML 页面，
                // 所以走官方 download-url 端点拿真实直链。
                val u = CurseForgeApi.fetchDownloadUrl(m)
                if (u.isBlank()) null else u to m.fileName
            } else if (m.source == "backend") {
                BackendApi.files(ctx, m.id, mc, ld).firstOrNull()?.let {
                    BackendApi.absolute(ctx, it.url) to it.name
                }
            } else {
                ModrinthApi.versions(m.id.ifBlank { m.slug }, mc, ld)
                    .firstOrNull()?.let { it.url to it.fileName }
            }
            if (pair == null || pair.first.isBlank()) {
                main { Tips.short(ctx, "这个模组暂时没有可下载的文件") }
                return@Thread
            }
            val dir = WorkDir.modsDir(ctx) ?: Targets.modsDir(ctx)
            if (dir == null) {
                main { Tips.short(ctx, "请先设置工作目录") }
                return@Thread
            }
            val name = pair.second.ifBlank { Downloader.guessName(pair.first) }
            val headers =
                if (m.source == "curseforge") CurseForgeApi.authHeaders() else emptyMap()
            val f = Downloader.download(ctx, pair.first, dir, name, headers)
            main { Tips.short(ctx, if (f == null) "下载失败" else "已安装：${f.name}") }
        } catch (t: Throwable) {
            main { Tips.short(ctx, "安装失败：${Err.humanMessage(t)}") }
        }
    }.start()
}

private fun main(b: () -> Unit) {
    android.os.Handler(android.os.Looper.getMainLooper()).post { b() }
}
