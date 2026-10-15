package com.kongbai.modmigrator

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment

/**
 * 开发者名单 + 致谢。
 *
 * 这是全项目第一个 Compose 页面，用来验证工具链能跑通。
 * 数据层（DevTeam）原样复用，只把渲染换成 Compose。
 *
 * 无论名单读不读得出来都会渲染：远端拿不到就用内置名单，
 * 真出错了也显示提示而不是留白。
 */
class DevsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        return ComposeView(ctx).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                McTheme(ctx) { DevsScreen(ctx) }
            }
        }
    }
}

@Composable
private fun DevsScreen(ctx: android.content.Context) {
    var list by remember { mutableStateOf<List<DevTeam.Dev>>(emptyList()) }
    var stateText by remember { mutableStateOf("正在读取名单…") }
    var loaded by remember { mutableStateOf(false) }

    if (!loaded) {
        loaded = true
        Bg.io.execute {
            val got = try {
                DevTeam.load(ctx)
            } catch (t: Throwable) {
                emptyList()
            }
            val fallback = if (got.isEmpty()) DevTeam.builtin() else got
            Bg.ui.post {
                list = fallback
                stateText = if (got.isEmpty()) "使用内置名单（${fallback.size} 项）"
                else "共 ${fallback.size} 位 · 点击卡片打开主页"
            }
        }
    }

    val people = list.filter { it.url.contains("github.com") }
    val others = list.filter { !it.url.contains("github.com") }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "感谢这些项目与个人。点击卡片可打开主页。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (list.isNotEmpty()) {
            Text(
                stateText,
                modifier = Modifier.padding(top = 8.dp),
                fontSize = 12.sp
            )
        } else {
            Text(stateText, modifier = Modifier.padding(top = 8.dp), fontSize = 12.sp)
        }
        LazyColumn(contentPadding = PaddingValues(top = 12.dp)) {
            if (people.isNotEmpty()) {
                item { SectionTitle("开发") }
                items(people) { d -> DevRow(ctx, d) }
            }
            if (others.isNotEmpty()) {
                item { SectionTitle("数据来源与协议") }
                items(others) { d -> DevRow(ctx, d) }
            }
            if (people.isEmpty() && others.isEmpty() && list.isNotEmpty()) {
                items(list) { d -> DevRow(ctx, d) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(vertical = 8.dp),
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun DevRow(ctx: android.content.Context, d: DevTeam.Dev) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clickable {
                if (d.url.isNotBlank()) WebActivity.open(ctx, d.url, d.name)
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(d.name, fontWeight = FontWeight.Bold)
            if (d.role.isNotBlank()) {
                Text(
                    d.role, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
