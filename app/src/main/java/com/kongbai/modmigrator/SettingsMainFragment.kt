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
        v.findViewById<Button>(R.id.btnGoStorage).setOnClickListener { go("storage") }
        v.findViewById<Button>(R.id.btnGoPlugin)?.setOnClickListener { go("plugin") }
        v.findViewById<Button>(R.id.btnGoDevs)?.setOnClickListener { go("devs") }
        v.findViewById<Button>(R.id.btnGoLog)?.setOnClickListener { go("log") }
        v.findViewById<Button>(R.id.btnGoAbout).setOnClickListener { go("about") }

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
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                    toast("在浏览器里完成授权后，回来点「登录 GitHub」刷新")
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
