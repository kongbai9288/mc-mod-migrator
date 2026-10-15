package com.kongbai.modmigrator

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage

/**
 * Compose 页面共用的几个小组件。
 *
 * 形状、间距、字号在这里定一处，避免每个页面各写一套。
 */

/** 空态：没有数据时占满整屏，写清为什么是空的、下一步怎么做 */
@Composable
fun McEmpty(title: String, body: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (body.isNotBlank()) {
                Text(
                    body,
                    modifier = Modifier.padding(top = 8.dp),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 模组卡片：图标 + 名称 + 摘要 + 右侧动作按钮 */
@Composable
fun McModCard(
    iconUrl: String,
    title: String,
    summary: String,
    action: String,
    onAction: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .let { m ->
                // 长按是移除收藏这类次要操作，不单独放按钮，避免误触
                if (onLongClick == null) m else m.combinedLongClick(onLongClick)
            },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = iconUrl.ifBlank { null },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, fontWeight = FontWeight.Bold, maxLines = 2)
                if (summary.isNotBlank()) {
                    Text(
                        summary,
                        fontSize = 12.sp,
                        maxLines = 2,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            androidx.compose.material3.TextButton(onClick = onAction) {
                Text(action)
            }
        }
    }
}

/** 次要说明文字 */
@Composable
fun McNote(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedLongClick(fn: () -> Unit): Modifier =
    combinedClickable(onLongClick = fn, onClick = {})

/**
 * Fragment 里的 onResume 在 Compose 中没有直接对应物。
 * 这里挂一次观察者，页面回到前台时回调 —— 收藏、回收站这类
 * 会被别的页面改动的数据，靠它回来就刷新。
 */
@Composable
fun ObserveResume(onResume: () -> Unit) {
    val cb by rememberUpdatedState(onResume)
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val ob = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) cb()
        }
        owner.lifecycle.addObserver(ob)
        onDispose { owner.lifecycle.removeObserver(ob) }
    }
}
