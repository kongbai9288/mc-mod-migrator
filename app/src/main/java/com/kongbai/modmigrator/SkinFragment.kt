package com.kongbai.modmigrator

import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import coil.compose.AsyncImage

/**
 * 正版玩家皮肤查询。
 *
 * 走 Mojang 公开 API（见 SkinApi），不需要登录、不需要 API Key。
 *
 * 这一页刻意做成**独立页面而不是弹窗**：
 * 结果里有图片预览、UUID、模型类型、披风，还有保存 / 复制链接几个操作，
 * 塞进弹窗在小屏上会被挤得只剩一半，而且弹窗里长按复制也不好用。
 */
class SkinFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        return ComposeView(ctx).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent { McTheme(ctx) { SkinScreen(ctx) } }
        }
    }
}

@Composable
private fun SkinScreen(ctx: android.content.Context) {
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var skin by remember { mutableStateOf<SkinApi.Skin?>(null) }
    var err by remember { mutableStateOf<String?>(null) }

    // 每秒走一次，用来刷新限速倒计时。只读 Prefs 里一串时间戳，开销可以忽略。
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }

    val gate = remember(tick, busy) { MojangRate.check(cost = MojangRate.PER_QUERY) }
    val left = remember(tick, busy) { MojangRate.remainingQueries() }

    fun go() {
        // 并发锁：连点不会堆出好几个网络请求
        if (busy) return
        val q = input.trim()
        if (q.isEmpty()) {
            err = "请输入玩家名或 UUID"
            return
        }
        // 先自己判一次，不行就别进后台了，直接把等待时间显示出来
        val v = MojangRate.check(cost = MojangRate.PER_QUERY)
        if (!v.ok) {
            err = v.reason + "，请 ${v.waitSec} 秒后再试"
            return
        }
        busy = true
        err = null
        skin = null
        Bg.io.execute {
            val r = runCatching { SkinApi.query(q) }
            Bg.post {
                busy = false
                tick++   // 查完剩余次数变了，立刻刷新
                r.onSuccess { skin = it }
                    .onFailure { err = it.message ?: "查询失败" }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        McNote("输入正版玩家名（比如 jeb_）或 UUID，可以查到他的皮肤和披风。走 Mojang 官方接口，不需要登录。")

        Spacer(Modifier.height(12.dp))

        McSearchField(input, { input = it }, "玩家名 或 UUID")

        Spacer(Modifier.height(10.dp))

        McFilledButton(
            when {
                busy -> "查询中…"
                !gate.ok -> "请等待 ${gate.waitSec} 秒"
                else -> "查询"
            },
            enabled = !busy && gate.ok
        ) { go() }

        Spacer(Modifier.height(6.dp))

        Text(
            if (!gate.ok) gate.reason + "，还需 ${gate.waitSec} 秒"
            else "今天还能查约 $left 次（Mojang 按 IP 计次，超了会封 IP，所以这里限得比较严）",
            fontSize = 12.sp,
            color = if (!gate.ok) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        if (busy) {
            Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp))
            }
        }

        err?.let { e ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    e,
                    Modifier.padding(14.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = 14.sp
                )
            }
        }

        skin?.let { s -> SkinResult(ctx, s) }
    }
}

@Composable
private fun SkinResult(ctx: android.content.Context, s: SkinApi.Skin) {
    var savedMsg by remember { mutableStateOf<String?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {

            Text(s.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(s.uuid, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.Top) {

                // 皮肤整图。方块、零圆角 —— 皮肤是像素图，圆角会把边角切掉。
                if (s.hasSkin) {
                    AsyncImage(
                        model = s.skinUrl,
                        contentDescription = "皮肤",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(112.dp)
                            .clip(RoundedCornerShape(0.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                } else {
                    Box(
                        Modifier
                            .size(112.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        Alignment.Center
                    ) {
                        Text("默认皮肤", fontSize = 11.sp)
                    }
                }

                Spacer(Modifier.width(14.dp))

                Column(Modifier.weight(1f)) {
                    Text("模型：" + s.modelText, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (s.hasSkin) "皮肤：已获取" else "皮肤：使用默认皮肤",
                        fontSize = 13.sp
                    )
                    if (!s.capeUrl.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text("披风：有", fontSize = 13.sp)
                    }
                }
            }

            if (!s.capeUrl.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Text("披风", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                AsyncImage(
                    model = s.capeUrl,
                    contentDescription = "披风",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .width(96.dp)
                        .height(160.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }

            if (s.hasSkin) {
                Spacer(Modifier.height(14.dp))
                //
                // McButtonRow 是具名参数（left/onLeft/right/onRight）。
                // 按位置传会把两个 String 当成 left/onLeft，直接编译不过。
                //
                McButtonRow(
                    left = "保存皮肤",
                    onLeft = {
                        savedMsg = null
                        Bg.io.execute {
                            val ok = runCatching { savePng(ctx, s.skinUrl!!, s.name) }
                            Bg.post {
                                savedMsg = if (ok.getOrNull() == true) "已保存到相册"
                                else "保存失败：" + (ok.exceptionOrNull()?.message ?: "")
                            }
                        }
                    },
                    right = "复制链接",
                    onRight = {
                        copyText(ctx, s.skinUrl!!)
                        savedMsg = "链接已复制"
                    }
                )
            }

            savedMsg?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/**
 * 复制到剪贴板。
 *
 * 项目里没有统一的 Copy 工具类（全仓库搜不到），各页面都是自己拿
 * ClipboardManager，这里也一样。
 */
private fun copyText(ctx: android.content.Context, text: String) {
    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager ?: return
    cm.setPrimaryClip(android.content.ClipData.newPlainText("skin url", text))
}

/**
 * 把皮肤 PNG 存进公共相册目录。
 *
 * Android 10（API 29）起不能直接往公共目录写文件，走 MediaStore；
 * 更老的系统用 File 直接写 Downloads。
 */
private fun savePng(ctx: android.content.Context, url: String, name: String): Boolean {
    val bytes = Http.call(url).use {
        if (!it.isSuccessful) error("HTTP " + it.code)
        it.body?.bytes() ?: error("空内容")
    }
    if (bytes.isEmpty()) error("空内容")

    // 文件名里的非法字符换成下划线，否则 MediaStore 插入会失败
    val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "skin" }

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$safe.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ModMigrator")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v
        ) ?: error("无法创建文件")
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: error("无法写入")
        v.clear()
        v.put(MediaStore.Images.Media.IS_PENDING, 0)
        ctx.contentResolver.update(uri, v, null, null)
        true
    } else {
        @Suppress("DEPRECATION")
        val dir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_PICTURES
        )
        val d = java.io.File(dir, "ModMigrator").apply { mkdirs() }
        java.io.File(d, "$safe.png").writeBytes(bytes)
        true
    }
}
