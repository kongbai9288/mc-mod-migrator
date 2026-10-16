package com.kongbai.modmigrator

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 设置 → 运行日志。
 *
 * 只显示末尾 400 行：超长文本一次性塞进文本框会卡界面。
 *
 * 读磁盘走后台：LogCenter.readAll 通过 ContentResolver 读文件，
 * 日志大了在主线程调用会 ANR。而且它是**有条件**读的——
 * 内存里有内容就不去读磁盘，老版本是无条件读一遍，纯属浪费。
 */
class SettingsLogFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        return ComposeView(ctx).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent { McTheme(ctx) { LogScreen(ctx) } }
        }
    }
}

@Composable
private fun LogScreen(ctx: android.content.Context) {
    var body by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    var errorsOnly by remember { mutableStateOf(false) }
    // 刷新靠这个计数重跑
    var gen by remember { mutableIntStateOf(0) }

    LaunchedEffect(gen, errorsOnly) {
        val errs = errorsOnly
        val mem = LogCenter.all()
        if (errs) {
            val e = mem.filter { it.isError }
            if (e.isNotEmpty()) {
                body = e.joinToString("\n") { it.toString() }
                info = "错误 ${e.size} 条"
                return@LaunchedEffect
            }
        } else if (mem.isNotEmpty()) {
            body = tail(mem.joinToString("\n") { it.toString() })
            info = "共 ${LogCenter.count()} 条（内存中）"
            return@LaunchedEffect
        }
        body = "正在读取磁盘日志…"
        val disk = withContext(Dispatchers.IO) {
            runCatching { LogCenter.readAll(ctx) }.getOrDefault("")
        }
        if (errs) {
            val lines = disk.split("\n").filter { it.contains("错误") }
            body = lines.joinToString("\n").ifBlank { "没有错误记录" }
            info = "内存中无错误"
        } else {
            body = tail(disk.ifBlank { "暂无日志" })
            info = "共 ${LogCenter.count()} 条（内存中）· 日志目录：${
                WorkDir.logs(ctx)?.name ?: "未设置工作目录"
            }"
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(Modifier.padding(bottom = 8.dp)) {
            McChipRow(listOf("全部", "只看错误"),
                if (errorsOnly) 1 else 0, { errorsOnly = it == 1 })
        }
        McButtonRow(
            "刷新",
            { gen++ },
            "清空",
            {
                LogCenter.clearFile(ctx)
                body = ""
                info = "已清空"
            }
        )
        if (info.isNotEmpty()) {
            Text(
                info, fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        }
        McMono(body, Modifier.horizontalScroll(rememberScrollState()))
    }
}

private fun tail(s: String): String {
    val lines = s.split("\n")
    return if (lines.size > 400) lines.takeLast(400).joinToString("\n") else s
}
