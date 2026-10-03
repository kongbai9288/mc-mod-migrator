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

    private val exec = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

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

        btnAccount.setOnClickListener { login() }
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
                    btnAccount.setOnClickListener { login() }
                    tvConnState.text = getString(R.string.account_hint_logged_out)

                    // 登不上时给两条后路：先看诊断，再不行就手动填 token。
                    // 之前只会反复重试同一个流程，失败了也没别的办法。
                    tvConnState.append("\n登不上？点「登录诊断」看卡在哪一步")

                    // 整行账号区域也可点：之前只有按钮能点，
                    // 用户点昵称/头像那一大片没反应，会以为点不动。
                    runCatching {
                        rowAccount.setOnClickListener { login() }
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

    private fun login() {
        val ctx = requireContext()
        //
        // 登录链路一共 4 步，任何一步断了表现都一模一样（就是登不上）。
        // 之前这里不记日志，用户只能看到"没反应"，既没法自查也没法报障。
        // 现在每一步都写进运行日志，并给一个「复制登录日志」的入口，
        // 用户可以直接把这条链路的完整记录发出来。
        //
        LogCenter.i("Login", "1. 用户点击登录，开始向后端要授权地址")
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
                try {
                    // 用内置浏览器登录：cookie 存在 WebView 里，
                    // OkHttp 通过 cookie 桥能读到，登录状态才对得上。
                    // 用结果回调启动，登录完成后会自动回到这里刷新状态。
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
                    "用访问令牌登录（推荐，直连 GitHub）",
                    "走后端 OAuth 登录",
                    "用系统浏览器登录",
                    "登录诊断（看卡在哪）",
                    "复制登录日志",
                    "退出登录"
                )
            ) { _, w ->
                when (w) {
                    0 -> manualToken()
                    1 -> login()
                    2 -> loginExternal()
                    3 -> runLoginDiag()
                    4 -> copyLoginLog()
                    5 -> doLogout()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 用系统浏览器完成授权。
     *
     * 内置浏览器走的是 WebView，GitHub 对嵌入式浏览器的支持并不完整，
     * 部分账号会卡在授权页或跳不回来。系统浏览器（Chrome）没有这个问题。
     *
     * 代价：会话 cookie 落在浏览器里，本应用读不到，
     * 所以回到应用后仍显示未登录——这是预期行为，不是故障。
     * 真正要让应用内也处于登录态，请改用「用访问令牌登录」。
     */
    private fun loginExternal() {
        val ctx = context ?: return
        LogCenter.i("Login", "用户选择用系统浏览器登录")
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
                LogCenter.i("Login", "已在系统浏览器打开 ${maskUrl(start.url)}")
                try {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW, android.net.Uri.parse(start.url))
                    )
                    Toast.makeText(
                        ctx,
                        "已在浏览器打开。授权完成后回到本页点「登录诊断」确认状态。\n" +
                            "注意：系统浏览器的登录态不会同步到应用内，\n" +
                            "需要应用内登录请用「用访问令牌登录」。",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (t: Throwable) {
                    Err.fail(t, "调起系统浏览器")
                    Toast.makeText(ctx, "打不开浏览器：${t.message}", Toast.LENGTH_LONG).show()
                }
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
        // 清理 Handler：页面销毁后若还有未执行的 post，
        // 回调里访问已销毁的 View 会直接崩。
        runCatching { handler.removeCallbacksAndMessages(null) }
    }

}
