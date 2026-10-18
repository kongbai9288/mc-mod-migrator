package com.kongbai.modmigrator

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import java.util.concurrent.atomic.AtomicInteger

/**
 * MC 资讯：官方最新版本 / 最新模组 / 最新整合包 / 最新光影。
 *
 * 四个分区各自独立抓取，任何一个挂了只影响它自己那一块，
 * 其余照常显示——不会出现"一个接口挂了整页空白"。
 */
class McFeedFragment : Fragment() {

    private var onReload: (() -> Unit)? = null
    private var refresh: androidx.swiperefreshlayout.widget.SwipeRefreshLayout? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val compose = ComposeView(ctx).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                McTheme(ctx) {
                    FeedScreen(
                        ctx,
                        register = { onReload = it },
                        onSettled = { refresh?.setRefreshing(false) }
                    )
                }
            }
        }
        // 下拉刷新手势目前没有 Compose 版的官方实现（material3 1.1 还没提供），
        // 这里沿用 View 的 SwipeRefreshLayout 包一层，手势和原来一致。
        return androidx.swiperefreshlayout.widget.SwipeRefreshLayout(ctx).apply {
            addView(
                compose,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            setOnRefreshListener { onReload?.invoke() }
            setRefreshing(true)
            refresh = this
        }
    }
}

@Composable
private fun FeedScreen(
    ctx: android.content.Context,
    register: (() -> Unit) -> Unit,
    onSettled: () -> Unit
) {
    val zones = remember { McFeed.labels() }
    var gen by remember { mutableStateOf(0) }
    // null = 这一块还在跑；空列表 = 跑完了但没抓到
    var results by remember { mutableStateOf<Map<String, List<McFeed.Item>?>>(emptyMap()) }

    DisposableEffect(Unit) {
        register { gen++ }
        onDispose { register({}) }
    }

    LaunchedEffect(gen) {
        // 只有手动下拉才清缓存；首次进入直接用 10 分钟内的缓存，省一次网络
        if (gen > 0) McFeed.clearCache()
        val done = AtomicInteger(0)
        for (z in zones) {
            Bg.io.execute {
                val list = try {
                    McFeed.fetch(ctx, z.key)
                } catch (t: Throwable) {
                    emptyList()
                }
                Bg.ui.post {
                    results = results + (z.key to list)
                    // 要等四块都回来才停转圈：只 post 一次的话转圈一闪就没了，
                    // 内容却要过几秒才出来，看着像刷新无效
                    if (done.incrementAndGet() >= zones.size) onSettled()
                }
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(14.dp)) {
        for (z in zones) {
            item { McSectionTitle(z.label) }
            val list = results[z.key]
            if (list == null) {
                item { McSkeleton(2) }
                item { McNote("正在从 ${z.site} 获取…") }
            } else if (list.isEmpty()) {
                item {
                    Text(
                        "暂时没抓到内容",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                item {
                    McNote("可能是网络不通，或站点改版导致解析失效。可以直接打开原站查看。")
                }
                item {
                    McFilledButton("打开 ${z.site}") { WebActivity.open(ctx, z.url, z.label) }
                }
            } else {
                items(list, key = { it.url.ifBlank { it.title } }) { e ->
                    McModCard(
                        iconUrl = "",
                        title = e.title,
                        summary = "来源：${e.source}${
                            if (e.extra.isNotBlank()) " · ${e.extra}" else ""
                        }",
                        action = "查看",
                        onAction = { WebActivity.open(ctx, e.url, e.title) }
                    )
                }
            }
        }
        item {
            McNote("以上内容分别来自各站点，版权归原作者所有，本应用仅作索引与跳转。")
        }
    }
}
