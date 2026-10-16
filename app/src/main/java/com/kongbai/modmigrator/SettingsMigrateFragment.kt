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
import androidx.fragment.app.Fragment
import kotlinx.coroutines.delay

/**
 * 设置 → 迁移默认值。
 *
 * 并发数下拉：View 的 Spinner 在**设置监听器时就会自动触发一次**回调，
 * 老版本必须加 spLoading 守卫，否则一进页面就把第 0 项（"1"）写进配置，
 * 你设的 4 会被悄悄改成 1。Compose 的下拉没有这个问题。
 */
class SettingsMigrateFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        return ComposeView(ctx).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent { McTheme(ctx) { MigrateScreen(ctx) } }
        }
    }
}

@Composable
private fun MigrateScreen(ctx: android.content.Context) {
    val prefs = remember { Prefs.get(ctx) }
    var defVer by remember { mutableStateOf(prefs.getString(K.DEF_VERSION, "") ?: "") }

    val loaders = remember {
        Loaders.visible(prefs.getBoolean(K.SHOW_EXTRA_LOADERS, false))
    }
    var loader by remember {
        val cur = Loaders.normalize(prefs.getString(K.DEF_LOADER, "auto") ?: "auto")
        mutableIntStateOf(loaders.indexOf(cur).let { if (it >= 0) it else 0 })
    }
    var autoInstall by remember { mutableStateOf(prefs.getBoolean(K.AUTO_INSTALL, true)) }
    var autoLaunch by remember { mutableStateOf(prefs.getBoolean(K.AUTO_LAUNCH, false)) }
    var autoSync by remember { mutableStateOf(prefs.getBoolean(K.AUTO_SYNC, false)) }

    val parLabels = remember { ctx.resources.getStringArray(R.array.parallel_labels).toList() }
    val parValues = remember { ctx.resources.getStringArray(R.array.parallel_values).toList() }
    var par by remember {
        val cur = prefs.getInt(K.DOWNLOAD_PARALLEL, 3)
        mutableIntStateOf(parValues.indexOf(cur.toString()).let { if (it >= 0) it else 2 })
    }

    fun put(block: android.content.SharedPreferences.Editor.() -> Unit) {
        runCatching { prefs.edit().apply(block).apply() }
    }

    LaunchedEffect(defVer) { delay(400); put { putString(K.DEF_VERSION, defVer.trim()) } }
    LaunchedEffect(loader) { put { putString(K.DEF_LOADER, loaders.getOrNull(loader) ?: "auto") } }
    LaunchedEffect(autoInstall) { put { putBoolean(K.AUTO_INSTALL, autoInstall) } }
    LaunchedEffect(autoLaunch) { put { putBoolean(K.AUTO_LAUNCH, autoLaunch) } }
    LaunchedEffect(par) {
        put { putInt(K.DOWNLOAD_PARALLEL, parValues.getOrNull(par)?.toIntOrNull() ?: 3) }
    }
    LaunchedEffect(autoSync) {
        put { putBoolean(K.AUTO_SYNC, autoSync) }
        // 改完要立刻重排定时任务，不然下次同步还是按旧开关走
        runCatching { SyncManager.schedule(ctx) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        McSectionTitle("默认值")
        McField(defVer, { defVer = it }, "默认 MC 版本（留空跟随最新）")
        McSpinner(loaders.map { Loaders.label(it) }, loader, { loader = it }, "默认加载器")

        McSectionTitle("自动化")
        McSwitchRow(autoInstall, { autoInstall = it }, "下载后自动安装")
        McSwitchRow(autoLaunch, { autoLaunch = it }, "安装后自动拉起游戏")
        McSwitchRow(autoSync, { autoSync = it }, "自动同步")

        McSectionTitle("下载")
        McSpinner(parLabels, par, { par = it }, "并发下载数")
    }
}
