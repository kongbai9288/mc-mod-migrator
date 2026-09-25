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

class SettingsMainFragment : Fragment() {

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

    /** 从登录页返回：刷 cookie → 取 token → 刷新界面 */
    private fun onLoginReturned() {
        val ctx = context ?: return
        toast("正在完成登录…")
        exec.execute {
            // cookie 落盘后再查，否则可能读到旧快照
            runCatching { WebCookies.flushAll() }
            // 后端有 token 接口就自动取回，不用用户手填
            val t = runCatching { BackendApi.fetchToken(ctx) }.getOrNull()
            if (!t.isNullOrBlank()) {
                Prefs.get(ctx).edit().putString(K.TOKEN, t).apply()
            }
            handler.post {
                if (!isAdded) return@post
                refreshAccount()
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
            Prefs.get(requireContext()).edit().putBoolean(K.OFFLINE, c).apply()
            toast(if (c) "已开启离线模式：只用本地词典与缓存，不发网络请求" else "已关闭离线模式")
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

        refreshAccount()
        return v
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

    private fun toast(s: String) {
        handler.post {
            if (!isAdded) return@post
            context?.let { Toast.makeText(it, s, Toast.LENGTH_SHORT).show() }
        }
    }

    /** 刷新登录态：已登录显示头像与昵称，未登录显示登录按钮 */
    private fun refreshAccount() {
        val ctx = requireContext()
        exec.execute {
            val u = try {
                BackendApi.me(ctx)
            } catch (t: Throwable) {
                null
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
        toast("正在打开授权页…")
        exec.execute {
            val url = try {
                BackendApi.loginUrl(ctx)
            } catch (t: Throwable) {
                ""
            }
            handler.post {
                if (!isAdded) return@post
                if (url.isBlank()) {
                    tvConnState.text = getString(R.string.conn_blocked_hint)
                    // 之前只说"连不上"，用户不知道是网络问题还是后端没配好。
                    // 现在直接把诊断入口摆出来，点一下就知道卡在哪。
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle("拿不到登录地址")
                        .setMessage(
                            "后端没返回 GitHub 授权地址。\n\n" +
                                "可能是网络不通（workers.dev 国内常不稳），" +
                                "也可能是后端还没配好 OAuth。\n\n" +
                                "要现在看一下是哪一步的问题吗？"
                        )
                        .setPositiveButton("诊断") { _, _ -> runLoginDiag() }
                        .setNegativeButton("离线使用", null)
                        .show()
                    return@post
                }
                try {
                    // 用内置浏览器登录：cookie 存在 WebView 里，
                    // OkHttp 通过 cookie 桥能读到，登录状态才对得上。
                    // 用结果回调启动，登录完成后会自动回到这里刷新状态。
                    val i = Intent(requireContext(), WebActivity::class.java)
                    i.putExtra("url", url)
                    i.putExtra("title", "登录 GitHub")
                    i.putExtra("login", true)
                    loginLauncher.launch(i)
                    toast("在页面里完成授权即可，完成后会自动刷新")
                } catch (t: Throwable) {
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
                if (r.loggedIn) refreshAccount()
            }
        }
    }

    /** 手动填 GitHub Token 兜底：后端这条路走不通时仍能使用相关功能 */
    private fun manualToken() {
        val ctx = context ?: return
        val et = android.widget.EditText(ctx).apply {
            hint = "ghp_xxxx 或 github_pat_xxxx"
            setSingleLine(true)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("手动填写 GitHub Token")
            .setMessage(
                "后端登录走不通时可以先这样用。\n\n" +
                    "Token 只存在本机，用于访问 GitHub API。\n" +
                    "建议只勾必要的只读权限。"
            )
            .setView(et)
            .setPositiveButton("保存") { _, _ ->
                val t = et.text.toString().trim()
                if (t.isBlank()) {
                    toast("没填内容")
                    return@setPositiveButton
                }
                Prefs.get(ctx).edit().putString(K.GH_TOKEN_BACKEND, t).apply()
                toast("已保存")
                refreshAccount()
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
                arrayOf("重新登录", "登录诊断（看卡在哪）", "手动填 GitHub Token")
            ) { _, w ->
                when (w) {
                    0 -> login()
                    1 -> runLoginDiag()
                    2 -> manualToken()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun doLogout() {
        val ctx = requireContext()
        exec.execute {
            try {
                BackendApi.logout(ctx)
            } catch (t: Throwable) {
                // 登出失败也按未登录处理
            }
            handler.post {
                if (!isAdded) return@post
                refreshAccount()
                toast("已退出")
            }
        }
    }
}
