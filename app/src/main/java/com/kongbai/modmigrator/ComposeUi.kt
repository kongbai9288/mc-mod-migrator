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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.clickable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.LifecycleOwner
import coil.compose.AsyncImage
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction

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
 * 页面回到前台时回调。
 *
 * Fragment 的 onResume 在 Composable 里没有直接对应物，
 * 所以由 Fragment 把「注册回调」的口子传进来：register(cb) 登记，
 * 页面销毁时自动注销。收藏、回收站这类会被别的页面改动的数据，
 * 靠它回来就刷新。
 *
 * @param register 由 Fragment 提供，登记后在其 onResume 里调用
 */
@Composable
fun ResumeEffect(register: (() -> Unit) -> Unit, onResume: () -> Unit) {
    val cb by rememberUpdatedState(onResume)
    DisposableEffect(Unit) {
        register { cb() }
        onDispose { register({}) }
    }
}

/** 生命周期观察者版，给能直接拿到 owner 的场景用 */
@Composable
fun ObserveResume(owner: LifecycleOwner, onResume: () -> Unit) {
    val cb by rememberUpdatedState(onResume)
    DisposableEffect(owner) {
        val ob = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) cb()
        }
        owner.lifecycle.addObserver(ob)
        onDispose { owner.lifecycle.removeObserver(ob) }
    }
}

/** 分组标题，列表里分隔几类内容用 */
@Composable
fun McSectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(vertical = 8.dp),
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}

/** 一行里的两个等宽按钮 */
@Composable
fun McButtonRow(
    left: String, onLeft: () -> Unit,
    right: String, onRight: () -> Unit
) {
    Row(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onLeft, modifier = Modifier.weight(1f)) { Text(left) }
        androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
        OutlinedButton(onClick = onRight, modifier = Modifier.weight(1f)) { Text(right) }
    }
}

@Composable
fun McFilledButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) { Text(text) }
}

/** 确认框：危险操作前问一次，避免误触 */
@Composable
fun McConfirm(
    title: String, body: String, confirmText: String,
    onConfirm: () -> Unit, onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 单选框：保留天数这类「几选一、选完立即生效」的场景 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun McPickOne(
    title: String, options: List<String>, selected: Int,
    onPick: (Int) -> Unit, onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEachIndexed { i, o ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(onClick = { onPick(i) })
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = i == selected, onClick = { onPick(i) })
                        Text(o, Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

/** 带勾选框的卡片行：批量操作用 */
@Composable
fun McCheckRow(
    checked: Boolean, onChecked: (Boolean) -> Unit, content: @Composable () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Box(Modifier.weight(1f)) { content() }
    }
}

/** 搜索框：列表页顶部常驻，输入即过滤 */
/** 开关行：标题 + 可选副标题，整行可点 */
@Composable
fun McSwitchRow(
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    title: String,
    sub: String? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChecked(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            if (!sub.isNullOrEmpty()) {
                Text(sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

/**
 * 下拉选择。
 *
 * Compose 的 ExposedDropdownMenu 没有"设置监听器就自动回调一次"的问题，
 * 不像 View 的 Spinner —— 那边必须加 loading 守卫，否则一进页面就
 * 把第 0 项写进配置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McSpinner(options: List<String>, selected: Int, onPick: (Int) -> Unit, label: String) {
    var open by remember { mutableStateOf(false) }
    val text = options.getOrNull(selected) ?: ""
    Column(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = text,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                trailingIcon = {
                    IconButton(onClick = { open = true }) { Text("▾", fontSize = 14.sp) }
                }
            )
            // 覆盖一层透明点击区：readOnly 的输入框点击不一定触发
            Box(
                Modifier
                    .matchParentSize()
                    .clickable { open = true }
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEachIndexed { i, o ->
                    DropdownMenuItem(
                        text = { Text(o, fontSize = 14.sp) },
                        onClick = { open = false; onPick(i) }
                    )
                }
            }
        }
    }
}

/** 单行输入框：hint 就是标签，不再另外配一行说明 */
@Composable
fun McField(value: String, onValueChange: (String) -> Unit, hint: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(hint, fontSize = 13.sp) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp)
    )
}

/** 可展开区：默认收起，点标题整行切换。用来装次要内容和长说明 */
@Composable
fun McExpand(title: String, expanded: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f))
            Text(if (expanded) "收起" else "展开", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) content()
    }
}

@Composable
fun McSearchField(value: String, onValueChange: (String) -> Unit, hint: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(hint, fontSize = 14.sp) },
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Search
        )
    )
}

/** 一排互斥的选项：排序方式这类 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun McChipRow(options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        options.forEachIndexed { i, o ->
            FilterChip(
                selected = i == selected,
                onClick = { onPick(i) },
                label = { Text(o, fontSize = 12.sp) },
                modifier = Modifier.padding(end = 8.dp)
            )
        }
    }
}

/** 骨架占位：数据没到之前占住卡片的位置，别让整页空白 */
@Composable
fun McSkeleton(lines: Int) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            repeat(lines) { i ->
                Box(
                    Modifier
                        .fillMaxWidth(if (i == 0) 0.7f else 1f)
                        .height(if (i == 0) 16.dp else 12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surface)
                )
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

/** 多按钮弹窗：详情类场景，操作多于两个时用它 */
@Composable
fun McActionDialog(
    title: String,
    onDismiss: () -> Unit,
    actions: List<Pair<String, () -> Unit>>,
    content: @Composable () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                content()
                actions.forEach { (label, act) ->
                    TextButton(onClick = act) { Text(label) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
