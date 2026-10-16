package com.kongbai.modmigrator

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment

/**
 * 同步页。
 *
 * 同步要往 GitHub 仓库读写，没登录时按钮点了只会默默失败 ——
 * 所以账号状态写在最上面，并且给一个直接去登录的入口。
 */
class SyncFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        return ComposeView(ctx).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                McTheme(ctx) {
                    SyncScreen(ctx, register = { cb -> onResumeCb = cb })
                }
            }
        }
    }

    private var onResumeCb: (() -> Unit)? = null

    override fun onResume() {
        super.onResume()
        onResumeCb?.invoke()
    }
}

@Composable
private fun SyncScreen(ctx: android.content.Context, register: (() -> Unit) -> Unit) {
    var gen by remember { mutableStateOf(0) }
    var loginText by remember { mutableStateOf("") }
    var loggedIn by remember { mutableStateOf(false) }
    var deviceLine by remember { mutableStateOf("") }
    var autoSync by remember { mutableStateOf(Prefs.get(ctx).getBoolean(K.AUTO_SYNC, false)) }
    var devices by remember { mutableStateOf<List<RemoteDevice>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }

    fun log(s: String) { logText = if (logText.isEmpty()) s else "$logText\n$s" }
    fun setState() {
        val p = Prefs.get(ctx)
        val token = p.getString(K.TOKEN, "").orEmpty()
        val login = p.getString(K.GH_LOGIN, "").orEmpty()
        loggedIn = token.isNotBlank()
        loginText = when {
            token.isBlank() -> "未登录：同步需要 GitHub 账号，否则上传和读取设备列表都会失败"
            login.isBlank() -> "已登录，可以同步"
            else -> "已登录：$login"
        }
        deviceLine = "ID ${Store.deviceId(ctx)} · ${Store.deviceLabel(ctx)} · " +
            "${p.getString(K.OWNER, "kongbai9288")}/${p.getString(K.REPO, "")}"
    }

    fun bg(block: () -> Unit) {
        busy = true
        Bg.io.execute {
            try {
                block()
            } catch (t: Throwable) {
                Bg.ui.post { log("错误：${t.message}") }
            } finally {
                // Progress 是全局单例，异常/提前 return 时不复位会永久卡在"运行中"
                try { Progress.done() } catch (_: Throwable) {}
                Bg.ui.post { busy = false }
            }
        }
    }

    DisposableEffect(Unit) {
        register { gen++ }
        onDispose { register({}) }
    }
    // 从设置页登录完回来要能看到新状态
    DisposableEffect(gen) {
        setState()
        onDispose {}
    }

    LazyColumn(Modifier.fillMaxSize().padding(14.dp)) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(loginText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(
                        deviceLine,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!loggedIn) {
                        Button(
                            onClick = { gotoLogin(ctx) },
                            modifier = Modifier.padding(top = 8.dp)
                        ) { Text("去设置里登录") }
                    }
                }
            }
        }
        item {
            McSwitchRow(
                checked = autoSync,
                onChecked = { c ->
                    autoSync = c
                    Prefs.get(ctx).edit().putBoolean(K.AUTO_SYNC, c).apply()
                    SyncManager.schedule(ctx)
                    Tips.short(ctx, if (c) "已开启自动同步" else "已关闭自动同步")
                },
                title = "自动同步",
                sub = "按固定间隔把本机清单推到仓库"
            )
        }
        item {
            Row(Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        if (Prefs.get(ctx).getString(K.TOKEN, "").isNullOrBlank()) {
                            setState()
                            Tips.short(ctx, "请先登录 GitHub")
                            return@OutlinedButton
                        }
                        log("开始上传…")
                        bg {
                            val msg = SyncManager.upload(ctx) { s -> Bg.ui.post { log(s) } }
                            Bg.ui.post { log(msg); Tips.short(ctx, msg) }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                ) { Text("上传") }
                Spacer(Modifier.size(8.dp))
                OutlinedButton(
                    onClick = {
                        if (Prefs.get(ctx).getString(K.TOKEN, "").isNullOrBlank()) {
                            setState()
                            Tips.short(ctx, "请先登录 GitHub")
                            return@OutlinedButton
                        }
                        Tips.short(ctx, "正在读取仓库…")
                        bg {
                            val list = SyncManager.devices(ctx)
                            Bg.ui.post {
                                devices = list
                                Tips.short(ctx, "远程设备 ${list.size} 个")
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                ) { Text("读取设备") }
            }
        }
        if (busy) item { McSkeleton(2) }
        if (devices.isNotEmpty()) {
            item { McSectionTitle("远程设备") }
            items(devices) { d ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(d.label, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        TextButton(onClick = {
                            log("开始从 ${d.label} 恢复…")
                            bg {
                                try {
                                    SyncManager.restore(ctx, d, true) { s ->
                                        Bg.ui.post { log(s) }
                                    }
                                    Bg.ui.post { log("恢复完成"); Tips.short(ctx, "恢复完成") }
                                } catch (t: Throwable) {
                                    Bg.ui.post { log("恢复失败：${t.message}") }
                                }
                            }
                        }) { Text("恢复") }
                    }
                }
            }
        }
        if (logText.isNotBlank()) {
            item { McSectionTitle("日志") }
            item { McMono(logText) }
        }
    }
}

private fun gotoLogin(ctx: android.content.Context) {
    val main = ctx as? MainActivity
    if (main != null) {
        main.openSettingsPage("main")
        return
    }
    runCatching {
        val i = android.content.Intent(ctx, SettingsHostActivity::class.java)
        i.putExtra("page", "main")
        ctx.startActivity(i)
    }.onFailure { Tips.short(ctx, "打不开设置页") }
}
