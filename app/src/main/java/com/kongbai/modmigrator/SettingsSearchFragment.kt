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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect

/**
 * 设置 → 搜索源。
 *
 * 「走后端」和「直连 CurseForge」互斥：开一个自动关另一个。
 * 老版本用一个 loading 标志位挡住联动触发的回调，
 * Compose 里开关状态由 remember 持有，互不触发，不需要守卫。
 */
class SettingsSearchFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        return ComposeView(ctx).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent { McTheme(ctx) { SearchScreen(ctx) } }
        }
    }
}

@Composable
private fun SearchScreen(ctx: android.content.Context) {
    val prefs = remember { Prefs.get(ctx) }
    val sources = remember { ctx.resources.getStringArray(R.array.sources2).toList() }
    var src by remember {
        mutableIntStateOf(sources.indexOf(prefs.getString(K.SOURCE, "聚合") ?: "聚合").coerceAtLeast(0))
    }
    var backend by remember { mutableStateOf(prefs.getBoolean(K.USE_BACKEND, true)) }
    var officialCf by remember { mutableStateOf(prefs.getBoolean(K.USE_OFFICIAL_CF, false)) }
    var cfKey by remember { mutableStateOf(prefs.getString(K.CF_KEY, "") ?: "") }
    var agg by remember { mutableStateOf(prefs.getBoolean(K.AGG_SEARCH, true)) }
    var rec by remember { mutableStateOf(prefs.getBoolean(K.RECOMMEND, true)) }
    var autoTrans by remember { mutableStateOf(prefs.getBoolean(K.AUTO_TRANS, true)) }
    var mirror by remember { mutableStateOf(prefs.getBoolean(K.USE_MIRROR, true)) }

    fun put(block: android.content.SharedPreferences.Editor.() -> Unit) {
        runCatching { prefs.edit().apply(block).apply() }
    }

    LaunchedEffect(src) { put { putString(K.SOURCE, sources.getOrNull(src) ?: "聚合") } }
    LaunchedEffect(agg) { put { putBoolean(K.AGG_SEARCH, agg) } }
    LaunchedEffect(rec) { put { putBoolean(K.RECOMMEND, rec) } }
    LaunchedEffect(autoTrans) { put { putBoolean(K.AUTO_TRANS, autoTrans) } }
    LaunchedEffect(mirror) { put { putBoolean(K.USE_MIRROR, mirror) } }
    // 400ms 防抖：每敲一个字符就写一次，粘贴一长串 key 会连续写入几十次
    LaunchedEffect(cfKey) { delay(400); put { putString(K.CF_KEY, cfKey.trim()) } }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        McSpinner(sources, src, { src = it }, "默认搜索源")

        McSectionTitle("CurseForge")
        McSwitchRow(backend, {
            backend = it
            if (it && officialCf) officialCf = false
            put {
                putBoolean(K.USE_BACKEND, it)
                if (it && prefs.getBoolean(K.USE_OFFICIAL_CF, false)) putBoolean(K.USE_OFFICIAL_CF, false)
            }
        }, "走自建后端", "不用自己申请 Key，但有配额限制")
        McSwitchRow(officialCf, {
            officialCf = it
            if (it && backend) backend = false
            put {
                putBoolean(K.USE_OFFICIAL_CF, it)
                if (it) putBoolean(K.USE_BACKEND, false)
            }
        }, "直连官方", "需要自己填 Key，配额高")
        McField(cfKey, { cfKey = it }, "CurseForge API Key")

        McSectionTitle("其它")
        McSwitchRow(agg, { agg = it }, "聚合搜索", "同时查多个平台再合并")
        McSwitchRow(rec, { rec = it }, "市场推荐")
        McSwitchRow(autoTrans, { autoTrans = it }, "自动翻译")
        McSwitchRow(mirror, { mirror = it }, "优先镜像", "国内直连不通时走镜像")
    }
}
