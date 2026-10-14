package com.kongbai.modmigrator

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.concurrent.Executors

class SyncFragment : Fragment() {

    private lateinit var tvLogin: TextView
    private lateinit var btnLogin: Button
    private lateinit var tvDeviceId: TextView
    private lateinit var tvLog: TextView
    private lateinit var swAutoSync: SwitchMaterial
    private lateinit var rvDevices: RecyclerView

    private val devices = mutableListOf<RemoteDevice>()
    private lateinit var adapter: DeviceAdapter
    private val exec = Bg.io
    private val handler = Bg.ui

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_sync, container, false)
        tvLogin = v.findViewById(R.id.tvLogin)
        btnLogin = v.findViewById(R.id.btnLogin)
        tvDeviceId = v.findViewById(R.id.tvDeviceId)
        tvLog = v.findViewById(R.id.tvLog)
        swAutoSync = v.findViewById(R.id.swAutoSync)
        rvDevices = v.findViewById(R.id.rvDevices)

        adapter = DeviceAdapter(devices) { d -> restore(d) }
        rvDevices.layoutManager = LinearLayoutManager(requireContext())
        rvDevices.adapter = adapter

        val ctx = requireContext()
        val p = Prefs.get(ctx)
        btnLogin.safeClickUnit { gotoLogin(ctx) }
        refreshLogin(ctx)
        tvDeviceId.text = "ID ${Store.deviceId(ctx)} · ${Store.deviceLabel(ctx)} · ${p.getString(K.OWNER, "kongbai9288")}/${p.getString(K.REPO, "")}"
        swAutoSync.isChecked = p.getBoolean(K.AUTO_SYNC, false)
        swAutoSync.setOnCheckedChangeListener { _, checked ->
            p.edit().putBoolean(K.AUTO_SYNC, checked).apply()
            SyncManager.schedule(ctx)
            toast(if (checked) "已开启自动同步" else "已关闭自动同步")
        }

        v.findViewById<Button>(R.id.btnUpload).safeClickUnit { upload() }
        v.findViewById<Button>(R.id.btnRefresh).safeClickUnit { refresh() }
        return v
    }

    /** 同步要往 GitHub 仓库读写，没登录时按钮点了只会默默失败，先把状态说清楚 */
    private fun refreshLogin(ctx: android.content.Context) {
        val p = Prefs.get(ctx)
        val token = p.getString(K.TOKEN, "").orEmpty()
        val login = p.getString(K.GH_LOGIN, "").orEmpty()
        if (token.isBlank()) {
            tvLogin.text = "未登录：同步需要 GitHub 账号，否则上传和读取设备列表都会失败"
            btnLogin.visibility = View.VISIBLE
        } else {
            tvLogin.text = if (login.isBlank()) "已登录，可以同步"
            else "已登录：$login"
            btnLogin.visibility = View.GONE
        }
    }

    private fun gotoLogin(ctx: android.content.Context) {
        val main = activity as? MainActivity
        if (main != null) {
            main.openSettingsPage("main")
            return
        }
        runCatching {
            val i = android.content.Intent(ctx, SettingsHostActivity::class.java)
            i.putExtra("page", "main")
            startActivity(i)
        }.onFailure { toast("打不开设置页") }
    }

    private fun log(s: String) {
        safePost(handler) { tvLog.append("$s\n") }
    }

    private fun toast(s: String) {
        handler.post {
            if (!isAdded) return@post
            try {
                context?.let { Tips.short(it, s) }
            } catch (t: Throwable) {
                // 界面已销毁，不弹
                     Err.ignore(t, "界面已销毁，不弹")
                 }
        }
    }

    private fun bg(block: () -> Unit) {
        exec.execute {
            try {
                block()
            } catch (t: Throwable) {
                log("错误：${t.message}")
            } finally {
                // 同 MigrationFragment：Progress 是全局单例，
                // 异常/提前 return 时不复位会永久卡在"运行中"，
                // 下次开 App 出现幽灵进度条。
                try { Progress.done() } catch (_: Throwable) {}
            }
        }
    }

    private fun upload() {
        val ctx = requireContext()
        if (Prefs.get(ctx).getString(K.TOKEN, "").isNullOrBlank()) {
            refreshLogin(ctx)
            toast("请先登录 GitHub")
            return
        }
        log("开始上传…")
        bg {
            val msg = SyncManager.upload(ctx) { log(it) }
            log(msg)
            toast(msg)
        }
    }

    private fun refresh() {
        val ctx = requireContext()
        if (Prefs.get(ctx).getString(K.TOKEN, "").isNullOrBlank()) {
            refreshLogin(ctx)
            toast("请先登录 GitHub")
            return
        }
        toast("正在读取仓库…")
        bg {
            val list = SyncManager.devices(ctx)
            safePost(handler) {
                devices.clear()
                devices.addAll(list)
                adapter.notifyDataSetChanged()
                toast("远程设备 ${list.size} 个")
            }
        }
    }

    private fun restore(d: RemoteDevice) {
        val ctx = requireContext()
        log("开始从 ${d.label} 恢复…")
        bg {
            try {
                SyncManager.restore(ctx, d, true) { log(it) }
                log("恢复完成")
                toast("恢复完成")
            } catch (t: Throwable) {
                log("恢复失败：${t.message}")
            }
        }
    }
    override fun onResume() {
        super.onResume()
        val ctx = context ?: return
        if (::tvLogin.isInitialized) refreshLogin(ctx)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 清理 Handler：页面销毁后若还有未执行的 post，
        // 回调里访问已销毁的 View 会直接崩。
        runCatching { handler.removeCallbacksAndMessages(null) }
    }

}
