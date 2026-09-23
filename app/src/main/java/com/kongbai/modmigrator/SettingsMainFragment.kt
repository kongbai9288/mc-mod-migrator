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
        v.findViewById<Button>(R.id.btnGoAbout).setOnClickListener { go("about") }

        refreshAccount()
        return v
    }

    private fun go(page: String) {
        // 已经在设置容器里就直接切 fragment，避免新开 Activity 造成返回栈错乱
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
        safePost(handler) { Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show() }
    }

    private fun refreshAccount() {
        val ctx = requireContext()
        exec.execute {
            try {
                val user = BackendApi.me(ctx)
                val probe = BackendApi.probe(ctx)
                safePost(handler) {
                    if (user != null) {
                        tvAccount.text = user.name.ifBlank { user.login }
                        tvConnState.text = probe.second
                        btnAccount.text = getString(R.string.account_logout)
                        btnAccount.setOnClickListener { logout() }
                        if (user.avatarUrl.isNotBlank()) {
                            ivAvatar.load(user.avatarUrl) { crossfade(true) }
                        }
                    } else {
                        tvAccount.text = getString(R.string.account_not_login)
                        // 连接不上时给安抚文案，而不是干巴巴的"失败"
                        tvConnState.text = if (probe.first) {
                            getString(R.string.account_hint_logged_out)
                        } else {
                            getString(R.string.conn_blocked_hint) + "（" + probe.second + "）"
                        }
                        btnAccount.text = getString(R.string.account_login)
                        btnAccount.setOnClickListener { login() }
                    }
                }

            } catch (t: Throwable) {
                // 后台异常不崩进程
            }
        }
    }

    private fun login() {
        val ctx = requireContext()
        toast("正在唤起 GitHub 授权…")
        exec.execute {
            try {
                val url = BackendApi.loginUrl(ctx)
                safePost(handler) {
                    if (url.isBlank()) {
                        val p = BackendApi.probe(ctx)
                        tvConnState.text = if (p.first) "后端没返回授权地址，稍后再试" else p.second
                        toast("连不上后端，已切换到离线可用功能")
                    } else {
                        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                    }
                }

            } catch (t: Throwable) {
                // 后台异常不崩进程
            }
        }
    }

    private fun logout() {
        BackendApi.logout(requireContext())
        toast("已退出登录")
        refreshAccount()
    }
}
