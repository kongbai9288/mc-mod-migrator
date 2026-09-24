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
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.concurrent.Executors

class SettingsMainFragment : Fragment() {

    private lateinit var ivAvatar: ImageView
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
        v.findViewById<Button>(R.id.btnGoAnim).setOnClickListener { go("anim") }
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
                    toast("连不上后端，可先离线使用")
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
