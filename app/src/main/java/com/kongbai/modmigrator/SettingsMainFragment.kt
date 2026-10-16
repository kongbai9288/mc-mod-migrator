package com.kongbai.modmigrator

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import coil.compose.AsyncImage

/** 读取登录态的最小间隔（毫秒） */
private const val ACCOUNT_FETCH_GAP = 10_000L

class SettingsMainFragment : Fragment() {

    // ---- 界面状态（登录逻辑写这些，界面读这些） ----
    private var accountText by mutableStateOf("")
    private var accountBtn by mutableStateOf("")
    private var connState by mutableStateOf("")
    private var avatarUrl by mutableStateOf("")
    private var loggedIn by mutableStateOf(false)

    private var swUpdate by mutableStateOf(true)
    private var swOffline by mutableStateOf(false)
    private var partSearch by mutableStateOf(false)
    private var partTranslate by mutableStateOf(false)
    private var partFeed by mutableStateOf(false)
    private var partUpdate by mutableStateOf(false)
    private var partAnnounce by mutableStateOf(false)
    private var swExtra by mutableStateOf(false)
    private var mode by mutableIntStateOf(0)

    private val exec = Bg.io
    private val handler = Bg.ui

    /**
     * 登录结果回调。
     *
     * 之前用 startActivity 打开登录页，回来后 Fragment 完全不知道登录已完成，
     * 界面还停在"未登录"——于是后面所有依赖登录的操作（后端搜索、同步、备份）
     * 都以为没登录，全部拒绝执行。这就是"登录成功但后续操作都不行"的根因。
     */
    private val loginLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        onLoginReturned()
    }

    private fun onLoginReturned() {
        val ctx = context ?: return
        toast("正在完成登录…")
        exec.execute {
            runCatching { WebCookies.flushAll() }
            handler.post {
                if (!isAdded) return@post
                refreshAccount(force = true)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAccount()
        // 从浏览器回到应用时把设备码那一屏重新摆出来：
        // 码只在弹窗出现过一次，切到浏览器再切回来弹窗已经没了，
        // 用户不知道该输什么，登录就卡死在这里、表现得像"点了没反应"。
        resumeDeviceLogin()
    }

    /** 续上没走完的那一轮设备流。设备码存在磁盘上，没过期就能接着用。 */
    private fun resumeDeviceLogin() {
        val ctx = context ?: return
        if (!isAdded) return
        if (Prefs.get(ctx).getBoolean(K.GH_PAT_OK, false)) return
        val pending = GhDeviceFlow.peek(ctx) ?: return
        if (deviceCodeShown) {
            if (!devicePolling) startDevicePolling()
            return
        }
        showDeviceCode(ctx, pending.first, pending.second)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val p = Prefs.get(ctx)
        swUpdate = p.getBoolean(K.UPDATE_CHECK, true)
        swOffline = p.getBoolean(K.OFFLINE, false)
        partSearch = p.getBoolean(K.OFFLINE_SEARCH, false)
        partTranslate = p.getBoolean(K.OFFLINE_TRANSLATE, false)
        partFeed = p.getBoolean(K.OFFLINE_FEED, false)
        partUpdate = p.getBoolean(K.OFFLINE_UPDATE, false)
        partAnnounce = p.getBoolean(K.OFFLINE_ANNOUNCE, false)
        swExtra = p.getBoolean(K.SHOW_EXTRA_LOADERS, false)
        mode = if (p.getInt(K.APP_MODE, 0) == 1) 1 else 0

        return ComposeView(ctx).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                McTheme(ctx) {
                    MainScreen(
                        accountText = accountText,
                        accountBtn = accountBtn,
                        connState = connState,
                        avatarUrl = avatarUrl,
                        loggedIn = loggedIn,
                        onAccount = { if (loggedIn) doLogout() else deviceLogin() },
                        onAccountLong = { showLoginMenu() },
                        swUpdate = swUpdate,
                        onUpdate = {
                            swUpdate = it
                            p.edit().putBoolean(K.UPDATE_CHECK, it).apply()
                            if (it) UpdateWorker.schedule(ctx) else UpdateWorker.cancel(ctx)
                            toast(if (it) "已开启更新提醒" else "已关闭更新提醒")
                        },
                        swOffline = swOffline,
                        onOffline = {
                            swOffline = it
                            p.edit().putBoolean(K.OFFLINE, it).apply()
                            toast(if (it) "已开启离线模式：所有联网功能一律关闭" else "已关闭离线模式")
                        },
                        partSearch = partSearch,
                        onSearch = { setPart(K.OFFLINE_SEARCH, it) { partSearch = it } },
                        partTranslate = partTranslate,
                        onTranslate = { setPart(K.OFFLINE_TRANSLATE, it) { partTranslate = it } },
                        partFeed = partFeed,
                        onFeed = { setPart(K.OFFLINE_FEED, it) { partFeed = it } },
                        partUpdate = partUpdate,
                        onPartUpdate = { setPart(K.OFFLINE_UPDATE, it) { partUpdate = it } },
                        partAnnounce = partAnnounce,
                        onAnnounce = { setPart(K.OFFLINE_ANNOUNCE, it) { partAnnounce = it } },
                        offlineOn = swOffline,
                        swExtra = swExtra,
                        onExtra = {
                            swExtra = it
                            p.edit().putBoolean(K.SHOW_EXTRA_LOADERS, it).apply()
                            toast(if (it) "已开启：加载器分类里会显示手机不支持的那些" else "已关闭")
                        },
                        mode = mode,
                        onMode = {
                            mode = it
                            p.edit().putInt(K.APP_MODE, it).apply()
                            toast(if (it == 1) "已切换到服务器端迁移" else "已切换到客户端迁移")
                        },
                        onGo = { go(it) }
                    )
                }
            }
        }
        // refreshAccount() 在构造之后调用：它只写状态，不碰 View
            .also { refreshAccount() }
    }

    /** 分项断网开关：控件文字是"关闭xxx的联网"，勾选 = 断网 */
    private fun setPart(key: String, v: Boolean, apply: () -> Unit) {
        Prefs.get(requireContext()).edit().putBoolean(key, v).apply()
        apply()
    }

    /** 补丁入口：之前只在启动时静默更新一次、失败也不说，用户根本不知道有这回事 */
    private fun go(page: String) {
        val host = activity as? SettingsHostActivity
        if (host != null) {
            host.show(page, true)
            return
        }
        // 设置入口在主界面里，子页直接在主界面容器内打开，
        // 不再另起 Activity —— 返回键的路径才是连贯的
        val main = activity as? MainActivity
        if (main != null) {
            main.openSettingsPage(page)
            return
        }
        val i = Intent(requireContext(), SettingsHostActivity::class.java)
        i.putExtra("page", page)
        startActivity(i)
    }

    /** 授权地址里带 state 参数，日志里只留域名和路径，不落参数 */
    private fun maskUrl(u: String): String {
        return try {
            val uri = android.net.Uri.parse(u)
            "${uri.scheme}://${uri.host}${uri.path}"
        } catch (t: Throwable) {
            u.take(60)
        }
    }

    private fun copyLoginLog() {
        val ctx = context ?: return
        val lines = LogCenter.all().filter {
            it.tag == "Login" || it.msg.contains("auth") || it.msg.contains("登录")
        }
        LogCenter.copyable(ctx, "登录日志", lines)
    }

    private fun toast(s: String) {
        handler.post { context?.let { android.widget.Toast.makeText(it, s, 2000).show() } }
    }

    /**
     * 上次真正发起「读取登录态」请求的时间。
     *
     * 真实日志里 15 秒内刷了 5 次完整的连接失败（每次带 4KB 堆栈）：
     * onCreateView / onResume / 登录回调 / 诊断页返回 都会触发刷新，
     * 而每次都要串行试多个后端入口、每个都要等满 6 秒连接超时。
     * 后端本来就不可达时，这等于在几秒内连打十几发必失败的请求。
     */
    @Volatile
    private var lastAccountFetch = 0L

    /** 刷新登录态：只更新状态字段，不碰 View */
    private fun refreshAccount(force: Boolean = false) {
        val ctx = context ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastAccountFetch < ACCOUNT_FETCH_GAP) return
        lastAccountFetch = now
        exec.execute {
            // 先看本机令牌：后端 workers.dev 一不通就必然"未登录"，
            // 用户明明已经用令牌登录过也被打回未登录状态。
            // 本机令牌是确定的事实，优先以它为准，后端只作补充。
            val pat = Prefs.get(ctx)
            val localLogin = pat.getString(K.GH_LOGIN, "") ?: ""
            val u = if (localLogin.isNotBlank() && pat.getBoolean(K.GH_PAT_OK, false)) {
                BackendApi.User(
                    login = localLogin,
                    name = "",
                    avatarUrl = pat.getString(K.GH_AVATAR, "") ?: ""
                )
            } else {
                runCatching { BackendApi.me(ctx) }.getOrNull()
            }
            handler.post {
                if (!isAdded) return@post
                if (u != null) {
                    loggedIn = true
                    accountText = "${u.name.ifBlank { u.login }}（已登录）"
                    accountBtn = "退出登录"
                    avatarUrl = u.avatarUrl
                    connState = "已连接后端"
                } else {
                    loggedIn = false
                    accountText = getString(R.string.account_not_login)
                    accountBtn = getString(R.string.account_login)
                    avatarUrl = ""
                    connState = getString(R.string.account_hint_logged_out) +
                        "\n登不上？长按登录按钮看诊断"
                }
            }
        }
    }

    /**
     * 走内置浏览器登录。
     *
     * 之前默认用系统浏览器（Chrome）绕开 passkey 在 WebView 里卡住的问题，
     * 但那会带来更致命的后果：state 校验必然失败。
     * 后端 Set-Cookie 的 mm_oauth_state 写在应用内 WebView 的 CookieManager，
     * 而回调请求是浏览器发出的，它的 jar 里根本没有这个 cookie，
     * 于是后端直接返回 {"error":"state 校验失败，请重新登录"}。
     * 换回内置浏览器后两步发生在同一个 WebView，cookie 对得上。
     */
    private fun login(useExternal: Boolean = false) {
        val ctx = requireContext()
        LogCenter.i("Login", "1. 开始登录：向后端请求授权地址")
        toast("正在打开授权页…")
        exec.execute {
            val start = try {
                BackendApi.loginUrl(ctx)
            } catch (t: Throwable) {
                BackendApi.LoginStart(error = Http.describeError(t))
            }
            handler.post {
                if (!isAdded) return@post
                if (start.url.isBlank()) {
                    LogCenter.e("Login", "2. 失败：${start.error}")
                    connState = start.error.ifBlank { "未知原因" }
                    showLoginError(ctx, connState)
                    return@post
                }
                if (!start.stateCookieOk) {
                    toast("注意：state cookie 未写入，授权后可能报校验失败")
                }
                LogCenter.i(
                    "Login",
                    "2. 已拿到授权地址（state cookie ${if (start.stateCookieOk) "已写入" else "缺失"}）"
                )
                if (useExternal) {
                    openExternal(ctx, start.url)
                    return@post
                }
                runCatching {
                    LogCenter.i("Login", "3. 拉起内置浏览器：${maskUrl(start.url)}")
                    val i = Intent(ctx, WebActivity::class.java)
                    i.putExtra("url", start.url)
                    i.putExtra("title", "登录 GitHub")
                    i.putExtra("login", true)
                    loginLauncher.launch(i)
                    toast("在页面里完成授权即可，完成后会自动刷新")
                }.onFailure {
                    Err.ignore(it, "打不开内置浏览器")
                    toast("打不开浏览器")
                }
            }
        }
    }

    private fun showLoginError(ctx: Context, reason: String) {
        android.app.AlertDialog.Builder(ctx)
            .setTitle("拿不到登录地址")
            .setMessage(
                "$reason\n\n常见原因：\n· workers.dev 在国内访问不稳定\n" +
                    "· 后端还没配好 OAuth（GH_OAUTH_CLIENT_ID 未设置）\n\n" +
                    "要现在看一下卡在哪一步吗？"
            )
            .setPositiveButton("诊断") { _, _ -> runLoginDiag() }
            .setNegativeButton("离线使用", null)
            .show()
    }

    /** 登录诊断：逐步测、逐个报 */
    private fun runLoginDiag() {
        val ctx = context ?: return
        val tv = android.widget.TextView(ctx).apply {
            text = "正在检查…\n"
            textSize = 13f
            setPadding(24, 16, 24, 16)
            setTextIsSelectable(true)
        }
        val sv = android.widget.ScrollView(ctx).apply { addView(tv) }
        android.app.AlertDialog.Builder(ctx)
            .setTitle("登录诊断")
            .setView(sv)
            .setPositiveButton("重新登录") { _, _ -> login() }
            .setNegativeButton(R.string.cancel, null)
            .show()

        exec.execute {
            val r = LoginDiag.run(ctx) { step ->
                handler.post {
                    if (!isAdded) return@post
                    tv.append("${if (step.ok) "✓" else "✗"} ${step.name}\n    ${step.detail}\n")
                }
            }
            handler.post {
                if (!isAdded) return@post
                val tip = LoginDiag.format(r).substringAfter("建议：", "")
                if (tip.isNotBlank()) tv.append("\n建议：$tip")
                if (r.loggedIn) refreshAccount(force = true)
            }
        }
    }

    /** 取 GitHub OAuth Client ID：本地存过就用，没存过去后端 /api/config 拿 */
    private fun withClientId(cb: (String) -> Unit) {
        val ctx = context ?: return
        val saved = Prefs.get(ctx).getString(K.GH_CLIENT_ID, "") ?: ""
        if (saved.isNotBlank()) {
            cb(saved)
            return
        }
        toast("正在取 Client ID…")
        exec.execute {
            val id = try {
                val o = Json.obj(Http.get(BackendApi.authBase() + "/api/config"))
                o?.let { Json.s(it, "githubClientId") } ?: ""
            } catch (t: Throwable) {
                ""
            }
            handler.post {
                if (!isAdded) return@post
                if (id.isBlank()) {
                    askClientId()
                } else {
                    Prefs.get(ctx).edit().putString(K.GH_CLIENT_ID, id).apply()
                    cb(id)
                }
            }
        }
    }

    /** 设备流轮询是否在进行中（页面销毁时要停，别空转） */
    private var devicePolling = false

    /** 设备码那一屏当前是否在屏幕上 */
    private var deviceCodeShown = false

    /**
     * 设备流登录 —— 默认入口。
     *
     * PKCE 一直报 `The client_id and/or client_secret is incorrect`，
     * 是因为 GitHub 的换 token 端点仍然要 client_secret，
     * PKCE 在它那儿只是附加项而不是替代。App 里又藏不住 secret，
     * 于是这条路从结构上就走不通。设备流只需要 client_id。
     */
    private fun deviceLogin() {
        val ctx = context ?: return
        // 上一轮还在有效期内就直接续上，别让用户再等一遍
        val pending = GhDeviceFlow.peek(ctx)
        if (pending != null) {
            showDeviceCode(ctx, pending.first, pending.second)
            return
        }
        toast("正在申请设备码…")
        withClientId { id ->
            if (id.isBlank()) return@withClientId
            exec.execute {
                val r = runCatching { GhDeviceFlow.start(id) }
                handler.post {
                    if (!isAdded) return@post
                    val st = r.getOrNull()
                    if (st == null) {
                        val msg = r.exceptionOrNull()?.message ?: "未知原因"
                        LogCenter.e("Login", "设备码申请失败：$msg")
                        android.app.AlertDialog.Builder(ctx)
                            .setTitle("拿不到设备码")
                            .setMessage(msg)
                            .setPositiveButton("重填 Client ID") { _, _ -> askClientId(force = true) }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                        return@post
                    }
                    GhDeviceFlow.save(ctx, id, GhDeviceFlow.DEFAULT_SCOPE, st)
                    LogCenter.i("Login", "设备流开始：user_code=${st.userCode}，${st.expiresIn}s 内有效")
                    showDeviceCode(ctx, st.userCode, st.verifyUri)
                }
            }
        }
    }

    /**
     * 把 8 位用户码摆给用户。
     * 手机上字母数字混排容易看错（I/1、O/0），所以放大单独一行并给复制按钮。
     */
    private fun showDeviceCode(ctx: Context, userCode: String, verifyUri: String) {
        if (!isAdded) return
        deviceCodeShown = true
        android.app.AlertDialog.Builder(ctx)
            .setTitle("第 1 步：在 GitHub 上输入这个码")
            .setMessage(
                "  $userCode\n\n" +
                    "这是一次性的授权码，不是密码，也不等于你的令牌。\n\n" +
                    "第 2 步：打开下面的地址，把码填进去、点授权。\n" +
                    "$verifyUri\n\n" +
                    "在手机上输、在电脑上输都行。\n" +
                    "输完不用做任何事 —— 回到这个页面就会自动完成登录。"
            )
            .setPositiveButton("打开授权页") { _, _ ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(verifyUri)))
                }.onFailure { toast("打不开浏览器") }
            }
            .setNeutralButton("复制码") { _, _ ->
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
                        as? android.content.ClipboardManager
                runCatching {
                    cm?.setPrimaryClip(
                        android.content.ClipData.newPlainText("GitHub 设备码", userCode)
                    )
                    toast("已复制：$userCode")
                }.onFailure { toast("复制失败，请手动记下") }
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(verifyUri)))
                }
            }
            .setNegativeButton("取消") { _, _ ->
                devicePolling = false
                deviceCodeShown = false
                GhDeviceFlow.clear(ctx)
            }
            .setOnCancelListener { devicePolling = false; deviceCodeShown = false }
            .show()
        startDevicePolling()
    }

    private fun finishDeviceLogin() {
        val ctx = context ?: return
        devicePolling = false
        deviceCodeShown = false
        GhDeviceFlow.clear(ctx)
    }

    /** 按 GitHub 给的间隔轮询，直到拿到令牌或这一轮作废 */
    private fun startDevicePolling() {
        if (devicePolling) return
        val ctx = context ?: return
        devicePolling = true
        var iv = GhDeviceFlow.currentInterval(ctx).toLong()
        LogCenter.i("Login", "设备流：开始轮询（每 ${iv}s）")

        fun step() {
            if (!devicePolling) return
            if (!isAdded) {
                devicePolling = false
                return
            }
            exec.execute {
                val r = GhDeviceFlow.poll(ctx)
                handler.post {
                    if (!isAdded) {
                        devicePolling = false
                        return@post
                    }
                    when (r) {
                        is GhDeviceFlow.Poll.Ok -> {
                            finishDeviceLogin()
                            onDeviceToken(ctx, r.token)
                        }
                        is GhDeviceFlow.Poll.Pending -> Unit
                        is GhDeviceFlow.Poll.SlowDown -> iv = r.interval.toLong()
                        is GhDeviceFlow.Poll.Expired -> {
                            finishDeviceLogin()
                            toast("设备码过期了，请重新点一次登录")
                        }
                        is GhDeviceFlow.Poll.Denied -> {
                            finishDeviceLogin()
                            toast("你在 GitHub 页面上取消了授权")
                        }
                        is GhDeviceFlow.Poll.Fail -> {
                            finishDeviceLogin()
                            LogCenter.e("Login", "设备流失败：${r.message}")
                            toast("登录失败：${r.message}")
                        }
                    }
                    if (devicePolling) handler.postDelayed({ step() }, iv * 1000L)
                }
            }
        }
        step()
    }

    private fun onDeviceToken(ctx: Context, token: String) {
        exec.execute {
            val user = GitHubApi.verifyPat(token).first
            handler.post {
                if (!isAdded) return@post
                if (user == null) {
                    toast("拿到令牌，但用它查不到账号信息")
                    return@post
                }
                Prefs.get(ctx).edit()
                    .putString(K.TOKEN, token)
                    .putString(K.GH_TOKEN_BACKEND, token)
                    // 这两项是"登录成功但没效果"的直接原因，之前漏了：
                    // 判断登没登录看的是 GH_LOGIN + GH_PAT_OK。
                    .putString(K.GH_LOGIN, user.login)
                    .putString(K.GH_AVATAR, user.avatarUrl)
                    .putBoolean(K.GH_PAT_OK, true)
                    .apply()
                LogCenter.i("Login", "设备流登录成功：${user.login}")
                toast("已登录：${user.login}")
                refreshAccount(force = true)
            }
        }
    }

    private fun pkceLogin() {
        val ctx = context ?: return
        val saved = Prefs.get(ctx).getString(K.GH_CLIENT_ID, "") ?: ""
        if (saved.isNotBlank()) {
            launchPkce(ctx, saved)
            return
        }
        toast("正在取 Client ID…")
        exec.execute {
            val id = try {
                val o = Json.obj(Http.get(BackendApi.authBase() + "/api/config"))
                o?.let { Json.s(it, "githubClientId") } ?: ""
            } catch (t: Throwable) {
                ""
            }
            handler.post {
                if (!isAdded) return@post
                if (id.isBlank()) askClientId()
                else {
                    Prefs.get(ctx).edit().putString(K.GH_CLIENT_ID, id).apply()
                    launchPkce(ctx, id)
                }
            }
        }
    }

    private fun askClientId(force: Boolean = false) {
        val ctx = context ?: return
        val cur = Prefs.get(ctx).getString(K.GH_CLIENT_ID, "") ?: ""
        if (force && cur.isNotBlank()) {
            android.app.AlertDialog.Builder(ctx)
                .setTitle("当前保存的 Client ID")
                .setMessage(
                    "$cur\n\n如果登录时报「client_id 不正确」，说明这个值不对。\n" +
                        "确认它是 **OAuth App**（不是 GitHub App）的 Client ID。"
                )
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton("清除") { _, _ ->
                    Prefs.get(ctx).edit().remove(K.GH_CLIENT_ID).apply()
                    toast("已清除，下次登录会重新问")
                }
                .setPositiveButton("重新填") { _, _ -> showClientIdInput(ctx, cur) }
                .show()
            return
        }
        showClientIdInput(ctx, cur)
    }

    private fun showClientIdInput(ctx: Context, cur: String) {
        val et = android.widget.EditText(ctx).apply {
            setSingleLine(true)
            if (cur.isNotBlank()) setText(cur)
        }
        android.app.AlertDialog.Builder(ctx)
            .setTitle("需要 GitHub OAuth Client ID")
            .setMessage(
                "后端连不上，取不到 Client ID。\n\n" +
                    "获取：GitHub → Settings → Developer settings →\n" +
                    "OAuth Apps → 你的应用 → Client ID。\n\n" +
                    "它不是机密，可以明文保存。\n\n" +
                    "另外请确认该应用的回调地址里有这一条：\n" + GhPkce.REDIRECT_URI
            )
            .setView(et)
            .setNeutralButton("打开 GitHub 设置页") { _, _ ->
                runCatching {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/settings/developers")
                        )
                    )
                }
                askClientId()
            }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton("保存并登录") { _, _ ->
                val id = et.text.toString().trim()
                if (id.isBlank()) {
                    toast("没填内容")
                    return@setPositiveButton
                }
                Prefs.get(ctx).edit().putString(K.GH_CLIENT_ID, id).apply()
                launchPkce(ctx, id)
            }
            .show()
    }

    private fun launchPkce(ctx: Context, clientId: String) {
        val v = GhPkce.newVerifier()
        GhPkce.saveVerifier(ctx, v)
        val url = GhPkce.authorizeUrl(clientId, v)
        LogCenter.i(
            "Login",
            "PKCE：拉起 Chrome Custom Tabs（client_id=" +
                clientId.take(4) + "…" + clientId.takeLast(4) + "，共 ${clientId.length} 位）"
        )
        val uri = android.net.Uri.parse(url)
        try {
            val i = androidx.browser.customtabs.CustomTabsIntent.Builder()
                .setSendToExternalDefaultHandlerEnabled(true)
                .build()
            i.intent.setData(uri)
            startActivity(i.intent)
        } catch (t: Throwable) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                .onFailure { toast("打不开浏览器") }
        }
        android.widget.Toast.makeText(
            ctx,
            "在浏览器里完成授权，会自动回到本应用。\n\n" +
                "如果 GitHub 提示 redirect_uri 不匹配，\n" +
                "需要在 OAuth App 的回调地址里加上：\n" + GhPkce.REDIRECT_URI,
            android.widget.Toast.LENGTH_LONG
        ).show()
    }

    private fun manualToken() {
        val ctx = context ?: return
        val et = android.widget.EditText(ctx).apply {
            hint = "ghp_xxxx 或 github_pat_xxxx"
            setSingleLine(true)
        }
        android.app.AlertDialog.Builder(ctx)
            .setTitle("用访问令牌登录")
            .setMessage(
                "后端（workers.dev）在国内经常连不上，\n" +
                    "这种情况下可以跳过后端，直连 GitHub。\n\n" +
                    "生成方法：GitHub → Settings → Developer settings →\n" +
                    "Personal access tokens → Tokens (classic) → Generate new token。\n\n" +
                    "令牌只存在本机，不会上传。"
            )
            .setView(et)
            .setPositiveButton("验证并保存") { _, _ ->
                val t = et.text.toString().trim()
                if (t.isBlank()) {
                    toast("没填内容")
                    return@setPositiveButton
                }
                toast("正在验证…")
                exec.execute {
                    val r = try {
                        GitHubApi.verifyPat(t)
                    } catch (e: Throwable) {
                        null to "验证出错：${e.message}"
                    }
                    handler.post {
                        if (!isAdded) return@post
                        val u = r.first
                        if (u == null) {
                            android.app.AlertDialog.Builder(ctx)
                                .setTitle("令牌不可用")
                                .setMessage(r.second)
                                .setPositiveButton(R.string.ok, null)
                                .show()
                            return@post
                        }
                        // 两个键都写：K.TOKEN 给 GitHubApi，GH_TOKEN_BACKEND 给后端路径
                        Prefs.get(ctx).edit()
                            .putString(K.TOKEN, t)
                            .putString(K.GH_TOKEN_BACKEND, t)
                            .putString(K.GH_LOGIN, u.login)
                            .putString(K.GH_AVATAR, u.avatarUrl)
                            .putBoolean(K.GH_PAT_OK, true)
                            .apply()
                        toast("已登录为 ${u.name.ifBlank { u.login }}")
                        refreshAccount(force = true)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 登录相关操作的菜单：整行和按钮长按都能出 */
    private fun showLoginMenu() {
        val ctx = context ?: return
        android.app.AlertDialog.Builder(ctx)
            .setTitle("登录")
            .setItems(
                arrayOf(
                    "用 GitHub 登录（设备流，推荐）",
                    "重填 / 查看 GitHub Client ID",
                    "用访问令牌登录（直连 GitHub）",
                    "用 PKCE 登录（GitHub 仍要 client_secret，多半失败）",
                    "登录诊断（看卡在哪）",
                    "复制登录日志",
                    "退出登录"
                )
            ) { _, w ->
                when (w) {
                    0 -> deviceLogin()
                    1 -> askClientId(force = true)
                    2 -> manualToken()
                    3 -> pkceLogin()
                    4 -> runLoginDiag()
                    5 -> copyLoginLog()
                    6 -> doLogout()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 在系统浏览器里打开授权地址。
     * 这条路一定会 state 校验失败（cookie 在应用内，浏览器 jar 里没有）。
     * 保留它只有一个用途：内置浏览器万一卡住，至少能在 Chrome 里完成 GitHub 那一步。
     */
    private fun openExternal(ctx: Context, url: String) {
        LogCenter.i("Login", "3. 在系统浏览器打开：${maskUrl(url)}")
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            android.widget.Toast.makeText(
                ctx,
                "已在系统浏览器打开 GitHub 登录页。\n\n" +
                    "注意：这条路大概率会报「state 校验失败，请重新登录」，\n" +
                    "因为后端的 state cookie 存在应用内，浏览器里没有。\n\n" +
                    "要让登录态真正生效，请用「用访问令牌登录」。",
                android.widget.Toast.LENGTH_LONG
            ).show()
        } catch (t: Throwable) {
            Err.fail(t, "调起系统浏览器")
            android.widget.Toast.makeText(ctx, "打不开浏览器", 3000).show()
        }
    }

    private fun loginExternal() {
        val ctx = context ?: return
        LogCenter.i("Login", "改用系统浏览器登录")
        exec.execute {
            val start = try {
                BackendApi.loginUrl(ctx)
            } catch (t: Throwable) {
                BackendApi.LoginStart(error = Http.describeError(t))
            }
            handler.post {
                if (!isAdded) return@post
                if (start.url.isBlank()) {
                    LogCenter.e("Login", "取地址失败：${start.error}")
                    toast("拿不到登录地址：${start.error}")
                    return@post
                }
                openExternal(ctx, start.url)
            }
        }
    }

    private fun doLogout() {
        val ctx = context ?: return
        exec.execute {
            runCatching { BackendApi.logout(ctx) }
            handler.post {
                if (!isAdded) return@post
                // 本机令牌也要清：不然刷新时又从本地读回账号，
                // 点了"退出登录"却还显示已登录
                Prefs.get(ctx).edit()
                    .putBoolean(K.GH_PAT_OK, false)
                    .putString(K.GH_LOGIN, "")
                    .putString(K.GH_AVATAR, "")
                    .apply()
                refreshAccount(force = true)
                toast("已退出")
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 停掉设备流轮询：否则页面销毁后它还在后台一圈一圈地问 GitHub，
        // 既耗电又会在回调里碰已销毁的 View
        devicePolling = false
    }
}

@Composable
private fun MainScreen(
    accountText: String,
    accountBtn: String,
    connState: String,
    avatarUrl: String,
    loggedIn: Boolean,
    onAccount: () -> Unit,
    onAccountLong: () -> Unit,
    swUpdate: Boolean,
    onUpdate: (Boolean) -> Unit,
    swOffline: Boolean,
    onOffline: (Boolean) -> Unit,
    partSearch: Boolean,
    onSearch: (Boolean) -> Unit,
    partTranslate: Boolean,
    onTranslate: (Boolean) -> Unit,
    partFeed: Boolean,
    onFeed: (Boolean) -> Unit,
    partUpdate: Boolean,
    onPartUpdate: (Boolean) -> Unit,
    partAnnounce: Boolean,
    onAnnounce: (Boolean) -> Unit,
    offlineOn: Boolean,
    swExtra: Boolean,
    onExtra: (Boolean) -> Unit,
    mode: Int,
    onMode: (Int) -> Unit,
    onGo: (String) -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {

        // ---------- 账号 ----------
        OutlinedCard(
            Modifier.fillMaxWidth(),
            onClick = onAccount
        ) {
            Row(
                Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (avatarUrl.isNotBlank()) {
                    AsyncImage(
                        model = avatarUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(accountText, fontSize = 14.sp)
                    if (connState.isNotBlank()) {
                        Text(
                            connState,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 15.sp
                        )
                    }
                }
                Text(
                    accountBtn,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
        McNote("长按这一行可以选诊断、手动填令牌等方式")

        // ---------- 通用 ----------
        McSectionTitle("通用")
        McSwitchRow(swUpdate, onUpdate, "检查应用更新", "开启后会定期检查新版本")
        McSwitchRow(swOffline, onOffline, "离线模式", "打开后所有联网功能一律关闭")

        // 总开关打开时分项置灰：此时分项不起作用，还让用户能勾会造成
        // "我明明单独开了商店，怎么还是连不上"的困惑
        val dim = if (offlineOn) 0.4f else 1f
        Column(Modifier.padding(start = 12.dp)) {
            McNote(if (offlineOn) "总开关已打开，下面各项不起作用" else "总开关关闭时，下面可以单独关某一块")
            PartSwitch("关闭搜索的联网", partSearch, !offlineOn, dim, onSearch)
            PartSwitch("关闭翻译的联网", partTranslate, !offlineOn, dim, onTranslate)
            PartSwitch("关闭资讯的联网", partFeed, !offlineOn, dim, onFeed)
            PartSwitch("关闭更新检查的联网", partUpdate, !offlineOn, dim, onPartUpdate)
            PartSwitch("关闭公告的联网", partAnnounce, !offlineOn, dim, onAnnounce)
        }
        McSwitchRow(
            swExtra,
            onExtra,
            "显示手机不支持的加载器",
            "开启后加载器分类里会多出这些选项"
        )

        // ---------- 迁移模式 ----------
        McSectionTitle("迁移模式")
        McChipRow(listOf("客户端迁移", "服务器端迁移"), mode, onMode)

        // ---------- 分组入口 ----------
        McSectionTitle("后端与搜索")
        EntryGrid(
            listOf(
                R.string.menu_backend to "backend",
                R.string.menu_search to "search"
            ),
            onGo
        )

        McSectionTitle("迁移与目录")
        EntryGrid(
            listOf(
                R.string.menu_migrate to "migrate",
                R.string.menu_storage to "storage"
            ),
            onGo
        )

        McSectionTitle("界面")
        EntryGrid(
            listOf(
                R.string.menu_translate to "translate",
                R.string.menu_nav to "nav"
            ),
            onGo
        )

        McSectionTitle("其他")
        EntryGrid(
            listOf(
                R.string.menu_about to "about",
                R.string.menu_log to "log"
            ),
            onGo
        )
    }
}

@Composable
private fun PartSwitch(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    alpha: Float,
    onChecked: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            modifier = Modifier.weight(1f)
        )
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onChecked,
            enabled = enabled
        )
    }
}

/** 两列入口网格 */
@Composable
private fun EntryGrid(entries: List<Pair<Int, String>>, onGo: (String) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    entries.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            row.forEach { (res, page) ->
                OutlinedCard(
                    onClick = { onGo(page) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 4.dp)
                ) {
                    Text(
                        ctx.getString(res),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp)
                    )
                }
            }
        }
    }
}
