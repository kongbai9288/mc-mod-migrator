package com.kongbai.modmigrator

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment

/**
 * 常用 MC 站点导航。
 * 用内置浏览器打开，长按可以用系统浏览器打开。
 */
class SitesFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        return ComposeView(ctx).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent { McTheme(ctx) { SitesScreen(ctx) } }
        }
    }
}

@Composable
private fun SitesScreen(ctx: android.content.Context) {
    var hintOpen by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(14.dp)) {
        item {
            McExpand("怎么用", hintOpen, { hintOpen = !hintOpen }) {
                McNote("点击用内置浏览器打开，长按用系统浏览器打开。内置浏览器里遇到下载链接会自动交给系统处理。")
            }
        }
        for (g in SitesConfig.groups()) {
            val list = SitesConfig.byGroup(g)
            if (list.isEmpty()) continue
            item { McSectionTitle(g) }
            items(list) { s -> SiteCard(ctx, s) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SiteCard(ctx: android.content.Context, s: SitesConfig.Site) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .combinedClickable(
                onClick = { WebActivity.open(ctx, s.url, s.name) },
                onLongClick = { openExternal(ctx, s.url) }
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(R.drawable.ti_external_link),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(s.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    s.desc,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun openExternal(ctx: android.content.Context, url: String) {
    try {
        ctx.startActivity(
            android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(url)
            )
        )
    } catch (t: Throwable) {
        Err.ignore(t, "打开站点链接")
    }
}
