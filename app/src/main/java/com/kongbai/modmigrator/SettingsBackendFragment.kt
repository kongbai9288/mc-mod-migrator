package com.kongbai.modmigrator

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import kotlinx.coroutines.delay

/**
 * 设置 → 后端。
 *
 * 两个地址 + 连通检测 + 回调地址登记。
 *
 * 回调地址那块收进折叠区：它是"出问题时才需要看"的排查信息，
 * 常驻会占掉大半屏，把真正要填的地址挤到下面。
 */
class SettingsBackendFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        return ComposeView(ctx).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent { McTheme(ctx) { BackendScreen(ctx) } }
        }
    }
}

@Composable
private fun BackendScreen(ctx: android.content.Context) {
    val prefs = remember { Prefs.get(ctx) }
    var base by remember { mutableStateOf(prefs.getString(K.BACKEND_BASE, "") ?: "") }
    var backup by remember { mutableStateOf(prefs.getString(K.BACKEND_BACKUP, "") ?: "") }
    var state by remember { mutableStateOf("") }
    var cb by remember { mutableStateOf("") }
    var cbOpen by remember { mutableStateOf(false) }

    fun save() {
        runCatching {
            prefs.edit()
                .putString(K.BACKEND_BASE, base.trim())
                .putString(K.BACKEND_BACKUP, backup.trim())
                .apply()
        }
    }

    // 400ms 防抖：每敲一个字符就写一次 SharedPreferences 的话，
    // 粘贴一长串地址会连续写入几十次
    LaunchedEffect(base, backup) {
        if (base.isEmpty() && backup.isEmpty()) return@LaunchedEffect
        delay(400)
        save()
    }
    // 退出时补落一次：否则敲完最后一个字符立刻退出会丢
    DisposableEffect(Unit) { onDispose { save() } }

    LaunchedEffect(Unit) {
        val c = runCatching { BackendApi.callbackUrl(ctx) }.getOrNull() ?: ""
        val all = runCatching { BackendApi.allCallbackUrls() }.getOrNull().orEmpty()
        cb = buildString {
            append("当前连通的后端：").append(runCatching { BackendApi.base(ctx) }.getOrNull()).append('\n')
            append("实际回调地址：").append(c).append("\n\n")
            append("后端每个入口对应一条回调地址，都要登记：\n")
            for (u in all) append("  · ").append(u).append('\n')
            append("\nGitHub 白名单里缺当前这条，就会报 redirect_uri 不匹配。")
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        McField(base, { base = it }, ctx.getString(R.string.cfg_backend_base))
        McField(backup, { backup = it }, ctx.getString(R.string.cfg_backend_backup))
        McNote(ctx.getString(R.string.cfg_backend_hint))

        McFilledButton(ctx.getString(R.string.btn_test_conn)) {
            state = "正在检测…"
            Bg.io.execute {
                val r = runCatching { BackendApi.probe(ctx) }
                Bg.ui.post {
                    // 之前异常只记日志，状态一直停在"正在检测…"，
                    // 用户永远等不到结果，看着像功能死了
                    val (ok, msg) = r.getOrElse { false to "检测出错：${it.message ?: "未知错误"}" }
                    state = msg
                    Tips.short(ctx, if (ok) "后端可用" else "后端不可用")
                }
            }
        }
        if (state.isNotEmpty()) McNote(state)

        McExpand("回调地址登记", cbOpen, { cbOpen = !cbOpen }) {
            if (cb.isEmpty()) McNote("正在读取…") else McNote(cb)
            McButtonRow(
                ctx.getString(R.string.cfg_copy_callback),
                {
                    Bg.io.execute {
                        val c = runCatching { BackendApi.callbackUrl(ctx) }.getOrNull() ?: ""
                        Bg.ui.post {
                            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("callback", c))
                            Tips.short(ctx, "已复制回调地址")
                        }
                    }
                },
                ctx.getString(R.string.cfg_gh_oauth),
                {
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://github.com/settings/developers")
                            )
                        )
                    }
                }
            )
            McNote(ctx.getString(R.string.cfg_callback_hint))
        }
    }
}
