package com.kongbai.modmigrator

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import coil.load
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.concurrent.Executors


/** 读取登录态的最小间隔（毫秒） */
private const val ACCOUNT_FETCH_GAP = 10_000L

class SettingsMainFragment : Fragment() {

    /** 初始化期间禁止触发监听器（Spinner/Switch 设置监听器时会自动回调一次） */
    private var loading = true


    private lateinit var ivAvatar: ImageView
    private lateinit var rowAccount: android.view.View
    private lateinit var tvAccount: TextView
    private lateinit var tvConnState: TextView
    private lateinit var btnAccount: Button
    private lateinit var swUpdate: SwitchMaterial
    private lateinit var swOffline: SwitchMaterial

    private val exec = Bg.io
    private val handler = Bg.ui

    /**
     * 登录结果回调。
     *
     * 之前用 startActivity 打开登录页，回来后 Fragment 完全不知道登录已完成，
     * 界面还停在"未登录"——于是后面所有依赖登录的操作（后端搜索、同步、备份）
     * 都以为没登录，全部拒绝执行。这就是"登录成功但后续操作都不行"的根因。
     * 现在用结果回调：登录页一结束就回来刷新状态、并自动取回 token。
     */
    private val loginLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        onLoginReturned()
    }

    /**
     * 从登录页返回：刷 cookie → 刷新界面。
     *
     * 之前这里还会调 BackendApi.fetchToken() 去"自动取回 token"，
     * 但后端根本没有 /api/auth/token 这个接口（详见 docs/接口对照-后端.md），
     * 那个请求必然 404，属于凭空编造。现在去掉这一步。
     *
     * 后端把 Client Secret 托管在服务端，本来也不该把 GitHub token 下发给客户端，
     * 所以这里只负责确认登录态并展示用户信息。
     */
    private fun onLoginReturned() {
        val ctx = context ?: return
        toast("正在完成登录…")
        exec.execute {
            // cookie 落盘后再查，否则可能读到旧快照
            runCatching { WebCookies.flushAll() }
            handler.post {
                if (!isAdded) return@post
                refreshAccount(force = true)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从登录页/其他页面回来时刷新一次登录态（防止状态显示滞后）
        if (::tvAccount.isInitialized) refreshAccount()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_settings_main, container, false)
        ivAvatar = v.findViewById(R.id.ivAvatar)
        rowAccount = v.findViewById(R.id.rowAccount)
        tvAccount = v.findViewById(R.id.tvAccount)
        tvConnState = v.findViewById(R.id.tvConnState)
        btnAccount = v.findViewById(R.id.btnAccount)
        swUpdate = v.findViewById(R.id.swUpdate)
        swOffline = v.findViewById(R.id.swOffline)

        swUpdate.isChecked = Prefs.get(requireContext()).getBoolean(K.UPDATE_CHECK, true)
        swUpdate.setOnCheckedChangeListener { _, c ->
            Prefs.get(requireContext()).edit().putBoolean(K.UPDATE_CHECK, c).apply()
            if (c) UpdateWorker.schedule(requireContext()) else UpdateWorker.cancel(requireContext())
            toast(if (c) "已开启更新提醒" else "已关闭更新提醒")
        }
        swOffline.isChecked = Prefs.get(requireContext()).getBoolean(K.OFFLINE, false)
        swOffline.setOnCheckedChangeListener { _, c ->
            if (loading) return@setOnCheckedChangeListener
            Prefs.get(requireContext()).edit().putBoolean(K.OFFLINE, c).apply()
            toast(if (c) "已开启离线模式：所有联网功能一律关闭" else "已关闭离线模式")
            syncOfflineParts(v)
        }

        // ---- 断网细分 ----
        // 之前只有一个总开关：想省某一块的流量，就只能把所有联网一起关掉。
        // 现在总开关关闭时，可以按块单独关。
        bindOfflinePart(v, R.id.swOfflineSearch, K.OFFLINE_SEARCH)
        bindOfflinePart(v, R.id.swOfflineTranslate, K.OFFLINE_TRANSLATE)
        bindOfflinePart(v, R.id.swOfflineFeed, K.OFFLINE_FEED)
        bindOfflinePart(v, R.id.swOfflineUpdate, K.OFFLINE_UPDATE)
        bindOfflinePart(v, R.id.swOfflineAnnounce, K.OFFLINE_ANNOUNCE)
        syncOfflineParts(v)

        // 手机不支持的加载器是否出现在分类下拉里
        val swExtra = v.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(
            R.id.swExtraLoaders
        )
        swExtra.isChecked = Prefs.get(requireContext())
            .getBoolean(K.SHOW_EXTRA_LOADERS, false)
        swExtra.setOnCheckedChangeListener { _, c ->
            if (loading) return@setOnCheckedChangeListener
            Prefs.get(requireContext()).edit().putBoolean(K.SHOW_EXTRA_LOADERS, c).apply()
            toast(if (c) "已开启：加载器分类里会显示手机不支持的那些" else "已关闭")
        }

        btnAccount.setOnClickListener { deviceLogin() }
        v.findViewById<Button>(R.id.btnGoBackend).setOnClickListener { go("backend") }
        v.findViewById<Button>(R.id.btnGoSearch).setOnClickListener { go("search") }
        v.findViewById<Button>(R.id.btnGoMigrate).setOnClickListener { go("migrate") }
        v.findViewById<Button>(R.id.btnGoTranslate).setOnClickListener { go("translate") }
        v.findViewById<Button>(R.id.btnGoNav).setOnClickListener { go("nav") }
        v.findViewById<Button>(R.id.btnGoStorage).setOnClickListener { go("storage") }
        v.findViewById<Button>(R.id.btnGoAbout).setOnClickListener { go("about") }
        v.findViewById<Button>(R.id.btnGoLab)?.setOnClickListener { go("lab") }
        v.findViewById<Button>(R.id.btnGoPatch)?.setOnClickListener { goPatch() }

        refreshAccount()
        loading = false
        return v
    }

    /**
     * 绑定一个细分断网开关。
     * 控件文字是"关闭xxx的联网"，所以勾选 = 存 true = 断网。
     */
    private fun bindOfflinePart(
        v: View, id: Int, key: String
    ) {
        val sw = v.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(id)
            ?: return
        sw.isChecked = Prefs.get(requireContext()).getBoolean(key, false)
        sw.setOnCheckedChangeListener { _, c ->
            if (loading) return@setOnCheckedChangeListener
            Prefs.get(requireContext()).edit().putBoolean(key, c).apply()
        }
    }

    /**
     * 总开关打开时把分项置灰 —— 此时分项不起作用，
     * 还让用户能勾会造成"我明明单独开了商店，怎么还是连不上"的困惑。
     */
    private fun syncOfflineParts(v: View) {
        val on = Prefs.get(requireContext()).getBoolean(K.OFFLINE, false)
        for (id in intArrayOf(
            R.id.swOfflineSearch, R.id.swOfflineTranslate, R.id.swOfflineFeed,
            R.id.swOfflineUpdate, R.id.swOfflineAnnounce
        )) {
            v.findViewById<android.view.View>(id)?.let {
                it.isEnabled = !on
                it.alpha = if (on) 0.4f else 1f
            }
        }
        v.findViewById<android.view.View>(R.id.tvOfflineParts)?.alpha = if (on) 0.4f else 1f
    }

    /**
     * 补丁入口。
     *
     * 之前这块只在 App 启动时后台静默更新一次、失败了也不说，
     * 用户根本不知道有这回事。现在给个显式入口，
     * 进去能看到每项的状态并手动刷新。
     */
    private fun goPatch() {
        go("patch")
    }

    private fun go(page: String) {
        // 已在设置容器里就直接切页，避免新开 Activity 造成返回栈错乱
        val host = activity as? SettingsHostActivity
        if (host != null) {
            host.show(page, true)
            return
        }
        // 设置入口现在在主界面里，子页也直接在主界面容器内打开，
        // 不再另起 Activity——这样返回键的路径是连贯的。
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

    /** 把登录链路的记录单独摘出来，方便直接发给开发者 */
    private fun copyLoginLog() {
        val ctx = context ?: return
        val lines = LogCenter.all().filter {
            it.tag == "Login" || it.msg.contains("auth") || it.msg.contains("登录")
        }
        LogCenter.copyable(ctx, "登录日志", lines)
    }

    private fun toast(s: String) {
        handler.post {
            if (!isAdded) return@post
            context?.let { Toast.makeText(it, s, Toast.LENGTH_SHORT).show() }
        }
    }

    /**
     * 上次真正发起「读取登录态」请求的时间。
     *
     * ⚠️ 真实日志里 15 秒内刷了 5 次完整的连接失败（每次带 4KB 堆栈）：
     * ```
     * 11:18:25 Connection reset
     * 11:18:28 SocketTimeoutException ... after 6000ms
     * 11:18:34 Failed to connect ...:443
     * 11:18:37 SocketTimeoutException ... after 6000ms
     * 11:18:40 Failed to connect ...:443
     * ```
     * 原因：onCreateView / onResume / 登录回调 / 诊断页返回 都会触发刷新，
     * 而每次都要串行试多个后端入口、每个都要等满 6 秒连接超时。
     * 后端本来就不可达时，这等于在几秒内连打十几发必失败的请求。
     *
     * 登录态不是实时行情，10 秒内刷一次完全够用。
     */
    @Volatile
    private var lastAccountFetch = 0L

    /** 刷新登录态：已登录显示头像与昵称，未登录显示登录按钮 */
    private fun refreshAccount(force: Boolean = false) {
        val ctx = requireContext()
        val now = System.currentTimeMillis()
        if (!force && now - lastAccountFetch < ACCOUNT_FETCH_GAP) {
            // 刚查过：只把已有状态渲染出来，不再打网络
            return
        }
        lastAccountFetch = now
        exec.execute {
            // ── 先看本机令牌 ──────────────────────────────
            // 之前只问后端：后端 workers.dev 一不通就必然"未登录"，
            // 用户明明已经用令牌登录过也被打回未登录状态。
            // 本机令牌是**确定的事实**，优先以它为准，后端只作补充。
            val pat = Prefs.get(ctx)
            val localLogin = pat.getString(K.GH_LOGIN, "") ?: ""
            val u = if (localLogin.isNotBlank() && pat.getBoolean(K.GH_PAT_OK, false)) {
                BackendApi.User(
                    login = localLogin,
                    name = "",
                    avatarUrl = pat.getString(K.GH_AVATAR, "") ?: ""
                )
            } else {
                try {
                    BackendApi.me(ctx)
                } catch (t: Throwable) {
                    null
                }
            }
            handler.post {
                if (!isAdded) return@post
                if (u != null) {
                    tvAccount.text = "${u.name.ifBlank { u.login }}（已登录）"
                    btnAccount.text = "退出登录"
                    btnAccount.setOnClickListener { doLogout() }
                    if (u.avatarUrl.isNotBlank()) {
                        try {
                            ivAvatar.load(u.avatarUrl) { crossfade(true) }
                        } catch (t: Throwable) {
                            // 头像加载失败不影响其他内容
                                 Err.ignore(t, "头像加载失败不影响其他内容")
                             }
                    }
                    tvConnState.text = "已连接后端"
                } else {
                    tvAccount.text = getString(R.string.account_not_login)
                    btnAccount.text = getString(R.string.account_login)
                    btnAccount.setOnClickListener { deviceLogin() }
                    tvConnState.text = getString(R.string.account_hint_logged_out)

                    // 登不上时给两条后路：先看诊断，再不行就手动填 token。
                    // 之前只会反复重试同一个流程，失败了也没别的办法。
                    tvConnState.append("\n登不上？点「登录诊断」看卡在哪一步")

                    // 整行账号区域也可点：之前只有按钮能点，
                    // 用户点昵称/头像那一大片没反应，会以为点不动。
                    runCatching {
                        rowAccount.setOnClickListener { pkceLogin() }
                        rowAccount.setOnLongClickListener {
                            showLoginMenu()
                            true
                        }
                    }
                    btnAccount.setOnLongClickListener {
                        showLoginMenu()
                        true
                    }
                }
            }
        }
    }

    /**
     * 走哪条路登录。
     *
     * ⚠️ 默认改成 **false（内置浏览器）**。
     *
     * 之前默认用系统浏览器（Chrome），目的是绕开
     * passkey（通行密钥）在 WebView 里卡住的问题。
     * 但这会带来一个更致命的后果：**state 校验必然失败**。
     *
     * 完整链路是这样的：
     *   1. 应用用 OkHttp 请求后端 /api/auth/login
     *   2. 后端在响应里 Set-Cookie: mm_oauth_state=xxx
     *      —— 这个 cookie 被写进**应用内**的 WebView CookieManager
     *   3. 浏览器打开 GitHub 授权页 → 用户授权
     *   4. GitHub 重定向到后端 /api/auth/callback?code=..&state=..
     *   5. 后端拿 URL 里的 state 和**请求带来的 cookie** 比对
     *
     * 第 2 步的 cookie 在应用里，而第 4 步的请求是**浏览器**发出的，
     * 浏览器的 jar 里根本没有 mm_oauth_state —— 于是第 5 步必然对不上，
     * 后端直接返回 {"error":"state 校验失败，请重新登录"}。
     * 用户看到的正是这一句，而且全程没有任何提示说"cookie 不在浏览器里"。
     *
     * 换回内置浏览器后，第 2 步和第 4 步发生在同一个 WebView 里，
     * cookie 对得上，state 校验就能过。
     *
     * passkey 的问题另外解决：登录模式改用桌面版 UA（见 WebActivity），
     * GitHub 在桌面 UA 下给的是常规账号密码表单，不再优先推 passkey。
     */
    private fun login(useExternal: Boolean = false) {
        val ctx = requireContext()
        //
        // 登录链路一共 4 步，任何一步断了表现都一模一样（就是登不上）。
        // 之前这里不记日志，用户只能看到"没反应"，既没法自查也没法报障。
        // 现在每一步都写进运行日志，并给一个「复制登录日志」的入口，
        // 用户可以直接把这条链路的完整记录发出来。
        //
        LogCenter.i("Login", "1. 开始登录：向后端请求授权地址")
        toast("正在打开授权页…")
        exec.execute {
            val start = try {
                BackendApi.loginUrl(ctx)
            } catch (t: Throwable) {
                // loginUrl 内部已兜住网络异常，这里只是最后一道保险
                BackendApi.LoginStart(error = Http.describeError(t))
            }
            handler.post {
                if (!isAdded) return@post
                if (start.url.isBlank()) {
                    LogCenter.e("Login", "2. 失败：${start.error}")
                    // 之前不管什么情况都显示同一句"连不上"，
                    // 用户分不清是网络问题还是后端没配好。
                    // 现在把后端返回的真实原因直接摆出来。
                    val reason = start.error.ifBlank { "未知原因" }
                    tvConnState.text = reason
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle("拿不到登录地址")
                        .setMessage(
                            "$reason\n\n" +
                                "常见原因：\n" +
                                "· workers.dev 在国内访问不稳定\n" +
                                "· 后端还没配好 OAuth（GH_OAUTH_CLIENT_ID 未设置）\n\n" +
                                "要现在看一下卡在哪一步吗？"
                        )
                        .setPositiveButton("诊断") { _, _ -> runLoginDiag() }
                        .setNegativeButton("离线使用", null)
                        .show()
                    return@post
                }
                if (!start.stateCookieOk) {
                    // 后端回调时要拿这个 cookie 做 CSRF 校验，
                    // 缺了它授权成功后仍会失败。提前讲清楚，别让用户白跑一趟。
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
                try {
                    // 内置浏览器：cookie 存在 WebView 里，
                    // OkHttp 通过 cookie 桥能读到，登录状态才对得上。
                    LogCenter.i("Login", "3. 拉起内置浏览器：${maskUrl(start.url)}")
                    val i = Intent(requireContext(), WebActivity::class.java)
                    i.putExtra("url", start.url)
                    i.putExtra("title", "登录 GitHub")
                    i.putExtra("login", true)
                    loginLauncher.launch(i)
                    toast("在页面里完成授权即可，完成后会自动刷新")
                } catch (t: Throwable) {
                    Err.ignore(t, "打不开内置浏览器")
                    toast("打不开浏览器")
                }
            }
        }
    }

    /**
     * 登录诊断：逐步测、逐个报。
     * 之前"登录不了"只能靠猜，因为链路有多个环节，断了的表现都一样。
     */
    private fun runLoginDiag() {
        val ctx = context ?: return
        val tv = android.widget.TextView(ctx).apply {
            text = "正在检查…\n"
            textSize = 13f
            setPadding(24, 16, 24, 16)
            setTextIsSelectable(true)
        }
        val sv = android.widget.ScrollView(ctx).apply { addView(tv) }
        val dlg = MaterialAlertDialogBuilder(ctx)
            .setTitle("登录诊断")
            .setView(sv)
            .setPositiveButton("重新登录") { _, _ -> login() }
            .setNegativeButton(R.string.cancel, null)
            .show()

        exec.execute {
            val r = LoginDiag.run(ctx) { step ->
                handler.post {
                    if (!isAdded) return@post
                    tv.append(
                        "${if (step.ok) "✓" else "✗"} ${step.name}\n    ${step.detail}\n"
                    )
                }
            }
            handler.post {
                if (!isAdded) return@post
                val full = LoginDiag.format(r)
                val tip = full.substringAfter("建议：", "")
                if (tip.isNotBlank()) tv.append("\n建议：$tip")
                if (r.loggedIn) refreshAccount(force = true)
            }
        }
    }

    /** 手动填 GitHub Token 兜底：后端这条路走不通时仍能使用相关功能 */
    /**
     * 用**个人访问令牌**登录。
     *
     * ⚠️ 之前这里只是把字符串存下来就完事，**从不校验**，
     * 而 `refreshAccount()` 判断"登没登录"只看后端 `me()`。
     * 后端 workers.dev 在国内连不上 → 永远返回 null →
     * 明明填了令牌却一直显示"未登录"，保存按钮像是坏了。
     *
     * 现在填完立刻调 `GitHubApi.verifyPat` 直连 GitHub 校验：
     * 成功就把账号信息落盘并显示，失败明确说为什么。
     */
    /**
     * PKCE 登录 —— 目前唯一一条能真正走通的路。
     *
     * 用 Chrome Custom Tabs（外部浏览器进程，passkey 可用）打开 GitHub，
     * 授权后靠自定义 scheme 把 code 交回应用，
     * 应用自己拿 code + verifier 去 GitHub 换 token。
     * 全程不经过后端，也就不存在 state cookie 在不同 jar 之间对不上的问题。
     */
    /**
     * 取 GitHub OAuth Client ID：本地存过就用，没存过去后端 /api/config 拿。
     *
     * 抽出来是因为设备流和 PKCE 都要这一步，
     * 之前各写一遍，改一处漏一处。
     */
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
                val b = BackendApi.authBase()
                val o = Json.obj(Http.get(b + "/api/config"))
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

    /**
     * 设备流登录 —— 现在的默认入口。
     *
     * 之前默认的 PKCE 之所以一直报
     * `The client_id and/or client_secret is incorrect`，
     * 是因为 GitHub 的换 token 端点**仍然要 client_secret**，
     * PKCE 在它那儿只是附加项而不是替代。App 里又藏不住 secret，
     * 于是这条路从结构上就走不通。
     * 设备流只需要 client_id，天然适合装在手机上的 App。
     */
    private fun deviceLogin() {
        val ctx = context ?: return
        // 上一轮还在有效期内就直接续上：
        // 用户可能已经输过码了，再申请一轮等于让他白等。
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
                        MaterialAlertDialogBuilder(ctx)
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
     *
     * 之前的设计依赖自定义 scheme 把浏览器"踢"回 App，
     * 而 scheme 回调在部分 ROM 上会被浏览器直接吞掉。
     * 设备流没有这个问题：授权在用户自己的浏览器里完成，
     * 我们只管轮询，谁也不需要回到谁。
     */
    private fun showDeviceCode(ctx: android.content.Context, userCode: String, verifyUri: String) {
        if (!isAdded) return
        MaterialAlertDialogBuilder(ctx)
            .setTitle("在 GitHub 上输入这个码")
            .setMessage(
                "$userCode\n\n" +
                    "打开下面的地址、把这 8 位码填进去并点授权。\n" +
                    "$verifyUri\n\n" +
                    "在任何设备上输都行，输完不用回到这里 —— 会自动检测到。"
            )
            .setPositiveButton("打开授权页") { _, _ ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(verifyUri)))
                }.onFailure { toast("打不开浏览器") }
            }
            .setNegativeButton("取消") { _, _ ->
                devicePolling = false
                GhDeviceFlow.clear(ctx)
            }
            .setOnCancelListener { devicePolling = false }
            .show()
        startDevicePolling()
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
                            devicePolling = false
                            onDeviceToken(ctx, r.token)
                        }
                        is GhDeviceFlow.Poll.Pending -> Unit   // 还没输，继续等
                        is GhDeviceFlow.Poll.SlowDown -> iv = r.interval.toLong()
                        is GhDeviceFlow.Poll.Expired -> {
                            devicePolling = false
                            toast("设备码过期了，请重新点一次登录")
                        }
                        is GhDeviceFlow.Poll.Denied -> {
                            devicePolling = false
                            toast("你在 GitHub 页面上取消了授权")
                        }
                        is GhDeviceFlow.Poll.Fail -> {
                            devicePolling = false
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

    private fun onDeviceToken(ctx: android.content.Context, token: String) {
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
                    .apply()
                LogCenter.i("Login", "设备流登录成功：${user.login}")
                toast("已登录：${user.login}")
                refreshAccount()
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
        // 后端 /api/config 公开了 clientId，先试着从那儿取，省得用户手填
        toast("正在取 Client ID…")
        exec.execute {
            val id = try {
                val b = BackendApi.authBase()
                val o = Json.obj(Http.get(b + "/api/config"))
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

    /**
     * 填 Client ID。
     * force=true 时用于"之前填错了，要改"——连不上的时候
     * 用户只能手填一次，填错就得有地方改回来。
     */
    private fun askClientId(force: Boolean = false) {
        val ctx = context ?: return
        val cur = Prefs.get(ctx).getString(K.GH_CLIENT_ID, "") ?: ""
        if (force && cur.isNotBlank()) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("当前保存的 Client ID")
                .setMessage(
                    "$cur\n\n" +
                        "如果登录时报「client_id 不正确」，说明这个值不对。\n" +
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

    private fun showClientIdInput(ctx: android.content.Context, cur: String) {
        val et = android.widget.EditText(ctx).apply {
            setSingleLine(true)
            if (cur.isNotBlank()) setText(cur)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("需要 GitHub OAuth Client ID")
            .setMessage(
                "后端连不上，取不到 Client ID。\n\n" +
                    "获取：GitHub → Settings → Developer settings →\n" +
                    "OAuth Apps → 你的应用 → Client ID。\n\n" +
                    "它不是机密，可以明文保存。\n\n" +
                    "另外请确认该应用的回调地址里有这一条：\n" +
                    GhPkce.REDIRECT_URI
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

    private fun launchPkce(ctx: android.content.Context, clientId: String) {
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
                // 允许 https 链接交回系统（App Links 那条才可能生效）
                .setSendToExternalDefaultHandlerEnabled(true)
                .build()
            i.intent.setData(uri)
            startActivity(i.intent)
        } catch (t: Throwable) {
            // 没有 Custom Tabs 就退回普通浏览器，scheme 回调照样能接住
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            }.onFailure { toast("打不开浏览器") }
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
        MaterialAlertDialogBuilder(ctx)
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
                            MaterialAlertDialogBuilder(ctx)
                                .setTitle("令牌不可用")
                                .setMessage(r.second)
                                .setPositiveButton(R.string.ok, null)
                                .show()
                            return@post
                        }
                        // 两个键都写：K.TOKEN 给 GitHubApi（备份/同步用），
                        // GH_TOKEN_BACKEND 给后端相关路径，避免只写一处另一处读不到
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

    /** 登录相关操作的菜单：重新登录 / 诊断 / 手动 Token。整行和按钮长按都能出。 */
    private fun showLoginMenu() {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
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
     *
     * 之所以默认走这里：GitHub 登录页现在默认推荐 passkey（通行密钥），
     * 它依赖系统级凭据绑定，**内置 WebView 不支持**，
     * 页面会一直停在 "Sign in with a passkey" 既不报错也不前进。
     * 系统浏览器支持 passkey 与常规账号密码，这条路才走得通。
     */
    private fun openExternal(ctx: android.content.Context, url: String) {
        LogCenter.i("Login", "3. 在系统浏览器打开：${maskUrl(url)}")
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            Toast.makeText(
                ctx,
                "已在系统浏览器打开 GitHub 登录页。\n\n" +
                    "注意：这条路大概率会报\n" +
                    "「state 校验失败，请重新登录」，\n" +
                    "因为后端的 state cookie 存在应用内，浏览器里没有。\n\n" +
                    "要让登录态真正生效，请用「用内置浏览器登录」\n" +
                    "或「用访问令牌登录」。",
                Toast.LENGTH_LONG
            ).show()
        } catch (t: Throwable) {
            Err.fail(t, "调起系统浏览器")
            Toast.makeText(ctx, "打不开浏览器", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 用系统浏览器完成授权。
     *
     * ⚠️ 这条路**一定会 state 校验失败**：
     * 后端种下的 mm_oauth_state cookie 存在应用内
     * （OkHttp 请求 /api/auth/login 后写进 WebView CookieManager），
     * 系统浏览器的 jar 里没有它。于是回调时后端比不上，直接返回
     * {"error":"state 校验失败，请重新登录"}。
     *
     * 保留它只有一个用途：内置浏览器万一卡住，
     * 至少能在真正的 Chrome 里完成 GitHub 那一步。
     * 登录态回不到应用是预期行为，界面上必须讲清楚，
     * 否则用户只会以为是登录功能坏了。
     */
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
                    Toast.makeText(ctx, "拿不到登录地址：${start.error}", Toast.LENGTH_LONG).show()
                    return@post
                }
                openExternal(ctx, start.url)
            }
        }
    }

    private fun doLogout() {
        val ctx = requireContext()
        exec.execute {
            try {
                BackendApi.logout(ctx)
            } catch (t: Throwable) {
                // 登出失败也按未登录处理
                     Err.ignore(t, "登出失败也按未登录处理")
                 }
            handler.post {
                if (!isAdded) return@post
                // 本机令牌也要一起清：不然刷新时又从本地读回账号，
                // 点了"退出登录"却还显示已登录。
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
        // 既耗电又会在回调里碰已销毁的 View。
        devicePolling = false
        // 清理 Handler：页面销毁后若还有未执行的 post，
        // 回调里访问已销毁的 View 会直接崩。
        runCatching { handler.removeCallbacksAndMessages(null) }
    }

}
