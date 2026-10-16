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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/**
 * 设置 → 翻译。
 *
 * 用 ML Kit 的离线模型。这里看模型状态、手动下载、直接试翻一段确认效果。
 */
class SettingsTranslateFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        return ComposeView(ctx).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent { McTheme(ctx) { TranslateScreen(ctx) } }
        }
    }
}

@Composable
private fun TranslateScreen(ctx: android.content.Context) {
    // 模型就绪状态会变，用计数驱动重查
    var gen by remember { mutableIntStateOf(0) }
    var input by remember { mutableStateOf("A mod that adds many new ores and tools to your world.") }
    var result by remember { mutableStateOf("") }

    val ready = remember(gen) { Translator.isReady() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        McNote(ctx.getString(R.string.translate_hint))
        Text(
            ctx.getString(R.string.translate_model_state) + "：" +
                (if (ready) ctx.getString(R.string.translate_ready)
                else ctx.getString(R.string.translate_not_ready)),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        McButtonRow(
            ctx.getString(R.string.translate_download),
            {
                Tips.short(ctx, ctx.getString(R.string.translate_downloading))
                Translator.ensureModel(ctx) { ok ->
                    gen++
                    Tips.long(
                        ctx,
                        if (ok) "模型下载完成，现在可以离线翻译了"
                        else "下载失败，请检查网络（模型约 30MB）"
                    )
                }
            },
            ctx.getString(R.string.translate_test),
            {
                val text = input.trim()
                if (text.isBlank()) return@McButtonRow
                result = "翻译中…"
                OfflineTranslate.translate(ctx, text) { zh ->
                    result = zh ?: "翻译失败：模型未下载且当前无法联网"
                }
            }
        )

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            minLines = 3,
            maxLines = 4,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
        )

        if (result.isNotEmpty()) {
            Text(
                result,
                fontSize = 14.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}
