package com.kongbai.modmigrator

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** 引导页选中的一个目录 */
private class FirDir(val name: String, val uri: String)

/** 引导页的全部界面状态。放在 Activity 字段里，SAF 回来也能改到。 */
private class Fir {
    var page by mutableStateOf(0)
    var step by mutableStateOf(0)
    var ready by mutableStateOf(false)

    /** true = 用应用专属目录（无需任何授权，开箱可用） */
    var useAppDir by mutableStateOf(true)
    var workUri by mutableStateOf("")

    val src = mutableStateListOf<FirDir>()
    val dst = mutableStateListOf<FirDir>()

    /** 非空就在页面顶部显示一条黄色警告 */
    var warn by mutableStateOf("")

    /** 启动器下拉选中项，-1 未选 */
    var launcherSel by mutableStateOf(-1)
    /** 在这台设备上探测到 / 手动指定的目录（给人看的那一段） */
    var foundPath by mutableStateOf("")

    var agreePrivacy by mutableStateOf(false)
    var agreeAi by mutableStateOf(false)
}

/**
 * 首次启动引导页（四页分页式）。
 *
 * ## 为什么改成四页
 *
 * 老版本是一整屏长滚动：工作目录、游戏目录、版本、加载器、镜像开关
 * 全挤在一页里，一屏装不下，用户看不到"一共要填几项"，
 * 也不知道填到哪一步了。现在一页一件事，每页都能完整看完。
 *
 * ## 三件事必须能在这一页办成，否则引导页等于没用
 *
 * 1. 目录授权被拒 → 不能卡死，给「应用专属目录」绕过。
 *    App 私有目录不需要任何存储授权，功能照样能用。
 * 2. 认不出启动器 → 下拉选；下拉里没有 → 「其他 / 手动指定」自己选 .minecraft。
 *    应用要对外发布，不能假设用户只用我们列过的那几个启动器。
 * 3. 迁移目录是**两个独立列表**、可多选、可逐条移除 ——
 *    用户往往有好几个旧版本目录要分批搬，原来只能存一个。
 *
 * ## 目录候选一律"存在才算"
 *
 * [LauncherDirs] 里标了证据等级，社区整理的条目只能当候选去试，
 * 探测不到就明说"在这台设备上没找到"，不给假路径。
 */
class FirstRunActivity : AppCompatActivity() {

    private val st = Fir()

    /** 启动器下拉的顺序（已装的排前面）。索引和 [launcherNames] 一一对应。 */
    private var launcherOrder: List<LauncherDirs.Entry> = emptyList()
    private var launcherNames: List<String> = emptyList()

    /** 手动指定 / 探测拿到的 uri，只在本次会话里用 */
    private var foundUri: String = ""

    // ---------------------------------------------------------- 目录授权

    private fun grant(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    private val pickWork = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            st.warn = getString(R.string.fir_warn_denied)
            return@registerForActivityResult
        }
        grant(uri)
        st.useAppDir = false
        st.workUri = uri.toString()
        st.warn = ""
    }

    private val pickSrc = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) st.warn = getString(R.string.fir_warn_denied)
        else addTo(st.src, uri, DirGuide.Purpose.SOURCE)
    }

    private val pickDst = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) st.warn = getString(R.string.fir_warn_denied)
        else addTo(st.dst, uri, DirGuide.Purpose.TARGET)
    }

    /**
     * 「其他 / 手动指定」：直接让用户选 .minecraft 那一层。
     *
     * 选完不直接进列表 —— 用户还要自己决定这个目录是「迁移前」还是「迁移后」。
     */
    private val pickManual = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            st.warn = getString(R.string.fir_warn_denied)
            return@registerForActivityResult
        }
        grant(uri)
        val s = uri.toString()
        st.foundPath = DirGuide.human(s).ifBlank { s }
        foundUri = s
        st.warn = DirGuide.check(this, uri, DirGuide.Purpose.SCAN_ROOT) ?: ""
    }

    private fun addTo(list: MutableList<FirDir>, uri: Uri, p: DirGuide.Purpose) {
        grant(uri)
        val s = uri.toString()
        if (list.any { it.uri == s }) return
        list.add(FirDir(DirGuide.human(s).ifBlank { s.substringAfterLast('/') }, s))
        st.warn = DirGuide.check(this, uri, p) ?: ""
    }

    // ---------------------------------------------------------- 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemePrefs.styleRes(this))
        super.onCreate(savedInstanceState)
        buildLauncherList()
        restore()

        setContent {
            McTheme(this) {
                FirHost(
                    st = st,
                    launcherNames = launcherNames,
                    onPickWork = { pickWork.launch(null) },
                    onPickSrc = { pickSrc.launch(null) },
                    onPickDst = { pickDst.launch(null) },
                    onBypass = {
                        st.useAppDir = true
                        st.workUri = ""
                        st.warn = ""
                    },
                    onLauncherPicked = { onLauncher(it) },
                    onAddFound = { toSrc ->
                        if (foundUri.isNotBlank()) {
                            val d = FirDir(st.foundPath.ifBlank { foundUri }, foundUri)
                            if (toSrc) {
                                if (st.src.none { it.uri == d.uri }) st.src.add(d)
                            } else {
                                if (st.dst.none { it.uri == d.uri }) st.dst.add(d)
                            }
                        }
                    },
                    onOpenInfo = { file, title -> openInfo(file, title) },
                    onFinish = { finishSetup() }
                )
            }
        }
    }

    /** 已装的启动器排前面，末尾固定挂「其他 / 手动指定」 */
    private fun buildLauncherList() {
        val all = LauncherDirs.all()
        val ins = all.filter { LauncherDirs.isInstalled(this, it) }
        val rest = all.filter { !LauncherDirs.isInstalled(this, it) }
        launcherOrder = ins + rest
        launcherNames = launcherOrder.map { it.name } + getString(R.string.fir_launcher_other)
    }

    /** 下拉选了某一项：在本机找它的目录，找不到就明说；最后一项走手动指定 */
    private fun onLauncher(idx: Int) {
        st.launcherSel = idx
        st.foundPath = ""
        foundUri = ""
        st.warn = ""
        if (idx < 0) return
        if (idx >= launcherOrder.size) {
            pickManual.launch(null)
            return
        }
        val e = launcherOrder[idx]
        val hit = LauncherDirs.detect(this).firstOrNull { it.first.key == e.key }
        if (hit == null) {
            st.warn = getString(R.string.fir_launcher_not_found)
            return
        }
        st.foundPath = hit.second.absolutePath
    }

    private fun openInfo(file: String, title: String) {
        val i = Intent(this, InfoActivity::class.java)
        i.putExtra("file", file)
        i.putExtra("title", title)
        startActivity(i)
    }

    /** 从开发者模式重跑时把上次填的带出来，不用重填一遍 */
    private fun restore() {
        val p = Prefs.get(this)
        val work = p.getString(K.WORKDIR_URI, null).orEmpty()
        st.workUri = work
        st.useAppDir = work.isBlank()
        loadList(P_SRC_LIST)?.let { st.src.addAll(it) }
        loadList(P_DST_LIST)?.let { st.dst.addAll(it) }
    }

    private fun loadList(key: String): List<FirDir>? {
        val raw = Prefs.get(this).getString(key, null) ?: return null
        return runCatching {
            val a = JSONArray(raw)
            val out = ArrayList<FirDir>(a.length())
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val uri = o.optString("uri")
                if (uri.isBlank()) continue
                out.add(FirDir(o.optString("name"), uri))
            }
            out
        }.getOrNull()
    }

    private fun saveList(key: String, list: List<FirDir>) {
        val a = JSONArray()
        for (d in list) {
            a.put(JSONObject().apply {
                put("name", d.name)
                put("uri", d.uri)
            })
        }
        Prefs.get(this).edit().putString(key, a.toString()).apply()
    }

    private fun finishSetup() {
        val ed = Prefs.get(this).edit()
        if (st.useAppDir) ed.remove(K.WORKDIR_URI) else ed.putString(K.WORKDIR_URI, st.workUri)
        saveList(P_SRC_LIST, st.src)
        saveList(P_DST_LIST, st.dst)
        // 迁移页目前还是单选，把每类的第一个直接填进去，进主页就能用
        st.src.firstOrNull()?.let { ed.putString(K.SRC_URI, it.uri) }
        st.dst.firstOrNull()?.let { ed.putString(K.DST_URI, it.uri) }
        ed.putString(K.TARGET_MC, McTargets.MC_VERSIONS[0])
        ed.putString(K.TARGET_LOADER, McTargets.LOADERS[0])
        ed.putBoolean(K.FIRST_RUN_DONE, true)
        ed.apply()
        goMain()
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        // 不要系统默认转场，否则会和最后那下淡出叠在一起闪一下
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val P_SRC_LIST = "fir_src_dirs"
        private const val P_DST_LIST = "fir_dst_dirs"
    }
}

// ============================================================ 界面

@Composable
private fun FirHost(
    st: Fir,
    launcherNames: List<String>,
    onPickWork: () -> Unit,
    onPickSrc: () -> Unit,
    onPickDst: () -> Unit,
    onBypass: () -> Unit,
    onLauncherPicked: (Int) -> Unit,
    onAddFound: (Boolean) -> Unit,
    onOpenInfo: (String, String) -> Unit,
    onFinish: () -> Unit
) {
    val ctx = LocalContext.current

    // 每页进场淡入。用 Animatable 而不是动画库里更高级的 API，少一层依赖风险
    val enter = remember { Animatable(0f) }
    LaunchedEffect(st.page) {
        enter.snapTo(0f)
        enter.animateTo(1f, animationSpec = tween(220, easing = LinearEasing))
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .alpha(enter.value),
        color = MaterialTheme.colorScheme.background
    ) {
        when (st.page) {
            0 -> PageWelcome(st)
            1 -> PageDirs(
                st, launcherNames,
                onPickWork, onPickSrc, onPickDst,
                onBypass, onLauncherPicked, onAddFound
            )
            2 -> PageAgree(st, onOpenInfo)
            3 -> PortalPage(onFinish)
        }
        if (st.page < 3) NavBar(st)
    }

    // 第 0 页的后台准备：真干活，不是假进度条
    if (st.page == 0 && !st.ready) {
        LaunchedEffect(Unit) {
            st.step = 0
            withContext(Dispatchers.IO) {
                DefaultDir.sub(ctx, "cache")
                DefaultDir.sub(ctx, "downloads")
            }
            delay(180)
            st.step = 1
            withContext(Dispatchers.IO) { LauncherDirs.detect(ctx) }
            delay(180)
            st.step = 2
            withContext(Dispatchers.IO) { McVer.refresh(ctx) }
            delay(200)
            st.step = 3
            st.ready = true
        }
    }
}

/** 底部导航：上一步 / 下一页 */
@Composable
private fun NavBar(st: Fir) {
    val ctx = LocalContext.current
    Box(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (st.page > 0) {
                OutlinedButton(
                    onClick = { st.page-- },
                    modifier = Modifier.weight(1f)
                ) { Text(ctx.getString(R.string.fir_back)) }
                Spacer(Modifier.width(10.dp))
            }
            val enabled = when (st.page) {
                0 -> st.ready
                1 -> (st.useAppDir || st.workUri.isNotBlank()) &&
                    st.src.isNotEmpty() && st.dst.isNotEmpty()
                2 -> st.agreePrivacy && st.agreeAi
                else -> true
            }
            Button(
                onClick = {
                    st.warn = ""
                    st.page++
                },
                enabled = enabled,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    when (st.page) {
                        0 -> ctx.getString(R.string.fir_continue)
                        2 -> ctx.getString(R.string.fir_enter)
                        else -> ctx.getString(R.string.fir_next)
                    }
                )
            }
        }
    }
}

// ------------------------------------------------------------ 第 1 页

@Composable
private fun PageWelcome(st: Fir) {
    val ctx = LocalContext.current
    val lines = listOf(
        "把 1.12.2 的存档，搬进新版本的世界。",
        "模组、配置、存档，一次搬完。",
        "跨加载器？也可以试试。",
        "先告诉它，东西放在哪。"
    )
    Column(
        Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))
        Text(
            ctx.getString(R.string.app_name),
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            ctx.getString(R.string.fir_welcome_sub),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(36.dp))
        Typewriter(lines)
        Spacer(Modifier.weight(1f))
        LoadSteps(st)
        Spacer(Modifier.height(72.dp))
    }
}

/**
 * 打字机：一句写完停一下，再逐字删掉，换下一句。
 * 光标用 ▌ 而不是 | —— 后者在部分字体里细得几乎看不见。
 */
@Composable
private fun Typewriter(lines: List<String>) {
    var text by remember { mutableStateOf("") }
    var cursor by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        var i = 0
        while (true) {
            val line = lines[i % lines.size]
            for (n in 1..line.length) {
                text = line.substring(0, n)
                delay(85)
            }
            delay(1500)
            for (n in line.length - 1 downTo 0) {
                text = line.substring(0, n)
                delay(42)
            }
            delay(380)
            i++
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            cursor = !cursor
            delay(520)
        }
    }
    Text(
        text = text + if (cursor) "▌" else " ",
        fontSize = 15.sp,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun LoadSteps(st: Fir) {
    val ctx = LocalContext.current
    val names = listOf(
        R.string.fir_step_dir,
        R.string.fir_step_launcher,
        R.string.fir_step_ver
    )
    Column(Modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = (st.step.toFloat() / names.size).coerceIn(0f, 1f),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        names.forEachIndexed { i, r ->
            val done = st.step > i
            Text(
                text = (if (done) "✓ " else "· ") + ctx.getString(r),
                fontSize = 12.sp,
                color = if (done) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
    }
}

// ------------------------------------------------------------ 第 2 页

@Composable
private fun PageDirs(
    st: Fir,
    launcherNames: List<String>,
    onPickWork: () -> Unit,
    onPickSrc: () -> Unit,
    onPickDst: () -> Unit,
    onBypass: () -> Unit,
    onLauncherPicked: (Int) -> Unit,
    onAddFound: (Boolean) -> Unit
) {
    val ctx = LocalContext.current
    val scroll = rememberScrollState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 20.dp)
            .padding(top = 20.dp, bottom = 88.dp)
    ) {
        Text(
            ctx.getString(R.string.fir_page_dir_title),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            ctx.getString(R.string.fir_page_dir_hint),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (st.warn.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            WarnCard(st.warn, onBypass)
        }

        // ---- 工作目录（单选）----
        Spacer(Modifier.height(16.dp))
        Text(ctx.getString(R.string.fir_work_title), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        ChoiceCard(
            title = ctx.getString(R.string.fir_work_app),
            desc = ctx.getString(R.string.fir_work_app_desc),
            picked = st.useAppDir
        ) {
            st.useAppDir = true
            st.workUri = ""
            st.warn = ""
        }
        Spacer(Modifier.height(8.dp))
        ChoiceCard(
            title = ctx.getString(R.string.fir_work_ext),
            desc = if (st.workUri.isBlank()) ctx.getString(R.string.fir_work_ext_desc)
            else DirGuide.human(st.workUri),
            picked = !st.useAppDir && st.workUri.isNotBlank()
        ) { onPickWork() }

        // ---- 迁移目录：两个独立列表 ----
        Spacer(Modifier.height(18.dp))
        Text(ctx.getString(R.string.fir_mig_title), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            ctx.getString(R.string.fir_need_both),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        DirList(
            title = ctx.getString(R.string.fir_src_list),
            items = st.src,
            onAdd = onPickSrc,
            onRemove = { st.src.removeAt(it) }
        )
        Spacer(Modifier.height(10.dp))
        DirList(
            title = ctx.getString(R.string.fir_dst_list),
            items = st.dst,
            onAdd = onPickDst,
            onRemove = { st.dst.removeAt(it) }
        )

        // ---- 认不出启动器时的兜底 ----
        Spacer(Modifier.height(18.dp))
        Text(
            ctx.getString(R.string.fir_launcher_title),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            ctx.getString(R.string.fir_launcher_hint),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        LauncherPicker(launcherNames, st.launcherSel, onLauncherPicked)

        if (st.foundPath.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        ctx.getString(R.string.fir_launcher_found, st.foundPath),
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { onAddFound(true) },
                            modifier = Modifier.weight(1f)
                        ) { Text(ctx.getString(R.string.fir_add_to_src), fontSize = 12.sp) }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { onAddFound(false) },
                            modifier = Modifier.weight(1f)
                        ) { Text(ctx.getString(R.string.fir_add_to_dst), fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}

/** 黄色警告卡：授权被拒 / 目录选错时的兜底入口 */
@Composable
private fun WarnCard(text: String, onBypass: () -> Unit) {
    val ctx = LocalContext.current
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFFFFF3CD),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFFE0B44A), RoundedCornerShape(10.dp))
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(text, fontSize = 12.sp, color = Color(0xFF6B4E00))
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onBypass) {
                Text(ctx.getString(R.string.fir_bypass), color = Color(0xFF6B4E00))
            }
        }
    }
}

@Composable
private fun ChoiceCard(
    title: String,
    desc: String,
    picked: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (picked) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.5.dp,
                if (picked) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(
                    desc,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (picked) {
                Text(
                    "✓",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/** 目录列表：可加多条，每条可移除 */
@Composable
private fun DirList(
    title: String,
    items: List<FirDir>,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit
) {
    val ctx = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$title（${items.size}）",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onAdd) {
                    Text(ctx.getString(R.string.fir_add_dir), fontSize = 12.sp)
                }
            }
            if (items.isEmpty()) {
                Text(
                    ctx.getString(R.string.fir_empty_list),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            } else {
                items.forEachIndexed { i, d ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(d.name, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onRemove(i) }) {
                            Text(
                                ctx.getString(R.string.fir_remove),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LauncherPicker(names: List<String>, sel: Int, onSel: (Int) -> Unit) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = true }
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (sel < 0) ctx.getString(R.string.fir_launcher_pick) else names[sel],
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            names.forEachIndexed { i, n ->
                DropdownMenuItem(
                    text = { Text(n, fontSize = 13.sp) },
                    onClick = { open = false; onSel(i) }
                )
            }
        }
    }
}

// ------------------------------------------------------------ 第 3 页

@Composable
private fun PageAgree(st: Fir, onOpenInfo: (String, String) -> Unit) {
    val ctx = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .padding(top = 20.dp, bottom = 88.dp)
    ) {
        Text(
            ctx.getString(R.string.fir_page_agree_title),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(14.dp))

        AgreeRow(
            checked = st.agreePrivacy,
            text = ctx.getString(R.string.fir_agree_privacy),
            onChecked = { st.agreePrivacy = it },
            onLink = { onOpenInfo("privacy.txt", ctx.getString(R.string.privacy_title)) }
        )
        Spacer(Modifier.height(6.dp))
        AgreeRow(
            checked = st.agreeAi,
            text = ctx.getString(R.string.fir_agree_ai),
            onChecked = { st.agreeAi = it }
        )

        Spacer(Modifier.height(18.dp))
        Row {
            TextButton(onClick = {
                onOpenInfo("licenses.txt", ctx.getString(R.string.license_title))
            }) { Text(ctx.getString(R.string.fir_link_oss), fontSize = 12.sp) }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                ctx.startActivity(
                    Intent(ctx, SettingsHostActivity::class.java).putExtra("page", "about")
                )
            }) { Text(ctx.getString(R.string.fir_link_about), fontSize = 12.sp) }
        }

        if (!st.agreePrivacy || !st.agreeAi) {
            Spacer(Modifier.height(10.dp))
            Text(
                ctx.getString(R.string.fir_need_agree),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AgreeRow(
    checked: Boolean,
    text: String,
    onChecked: (Boolean) -> Unit,
    /** 非空才显示右侧「查看」；AI 说明那条没有对应文档，就不放 */
    onLink: (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Text(
            text,
            fontSize = 13.sp,
            modifier = Modifier
                .weight(1f)
                .clickable { onChecked(!checked) }
        )
        if (onLink != null) {
            TextButton(onClick = onLink) { Text("查看", fontSize = 11.sp) }
        }
    }
}

// ------------------------------------------------------------ 第 4 页：传送门

/**
 * 传送门动画。
 *
 * 分四段：黑曜石框出现 → 内部点燃（螺旋星尘 + 紫光）→ 紫光膨胀铺满 → 白光淡出。
 * 内部画的是螺旋 + 闪烁星点，不是纯色块 —— 模拟加载了材质包的那种质感。
 *
 * 动画关掉（低配机或用户手动关）时走同一条时间轴，只是压到 900ms，
 * 不会因为跳过动画就"啪"地切走、看着像闪退。
 */
@Composable
private fun PortalPage(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val total = if (AnimPrefs.enabled(ctx)) 2400 else 900
    val p = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        p.animateTo(1f, animationSpec = tween(total, easing = LinearEasing))
        onDone()
    }

    val t = p.value

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val portalH = min(w, h) * 0.62f
        val portalW = portalH * 0.62f
        val r0 = portalH * 0.5f
        val maxR = min(w, h) * 1.6f

        val frameA = (t / 0.10f).coerceIn(0f, 1f)
        val swirlA = ((t - 0.10f) / 0.22f).coerceIn(0f, 1f)
        val expand = ((t - 0.34f) / 0.34f).coerceIn(0f, 1f)
        val eased = 1f - (1f - expand) * (1f - expand)
        val whiteA = when {
            t < 0.68f -> 0f
            t < 0.80f -> (t - 0.68f) / 0.12f
            else -> (1f - (t - 0.80f) / 0.20f).coerceIn(0f, 1f)
        }
        val rot = t * 900f

        // 背景：黑曜石之外的夜色
        drawRect(Color(0xFF08060C))

        // 紫光，随 expand 一路涨到铺满屏幕
        val rNow = r0 + (maxR - r0) * eased
        for (i in 46 downTo 1) {
            val f = i.toFloat() / 46f
            drawCircle(
                color = Color(0xFF7B2FF7).copy(alpha = swirlA * f * 0.16f),
                radius = rNow * f,
                center = Offset(cx, cy)
            )
        }
        drawCircle(
            color = Color(0xFFD9A6FF).copy(alpha = swirlA * 0.55f),
            radius = rNow * 0.35f,
            center = Offset(cx, cy)
        )

        // 螺旋星尘：只画在门框内，膨胀到铺满之后就没必要再画了
        if (expand < 0.9f) {
            clipRect(
                left = cx - portalW / 2f,
                top = cy - portalH / 2f,
                right = cx + portalW / 2f,
                bottom = cy + portalH / 2f
            ) {
                for (i in 0 until 110) {
                    val f = i / 110f
                    val ang = (f * 360f * 5f + rot) * PI / 180.0
                    val rad = r0 * (0.12f + 0.88f * (1f - f))
                    val px = cx + (cos(ang) * rad).toFloat()
                    val py = cy + (sin(ang) * rad).toFloat()
                    val tw = 0.35f + 0.65f *
                        (sin((t * 12f + i).toDouble()).toFloat() * 0.5f + 0.5f)
                    drawCircle(
                        color = Color(0xFFEADFFF).copy(alpha = swirlA * tw * 0.85f),
                        radius = 1.2f + 3.4f * (1f - f),
                        center = Offset(px, py)
                    )
                }
            }
        }

        // 黑曜石框
        if (expand < 0.6f) {
            val fa = frameA * (1f - (expand / 0.6f).coerceIn(0f, 1f))
            val tl = Offset(cx - portalW / 2f, cy - portalH / 2f)
            val sz = Size(portalW, portalH)
            drawRoundRect(
                color = Color(0xFF1A1424).copy(alpha = fa),
                topLeft = tl,
                size = sz,
                cornerRadius = CornerRadius(10f, 10f)
            )
            drawRoundRect(
                color = Color(0xFF6B21A8).copy(alpha = fa * 0.9f),
                topLeft = tl,
                size = sz,
                cornerRadius = CornerRadius(10f, 10f),
                style = Stroke(width = 6f)
            )
        }

        // 白光淡出
        if (whiteA > 0f) drawRect(Color.White.copy(alpha = whiteA))
    }
}
