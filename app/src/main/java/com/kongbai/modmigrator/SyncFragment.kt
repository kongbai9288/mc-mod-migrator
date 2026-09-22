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

    private lateinit var tvDeviceId: TextView
    private lateinit var tvLog: TextView
    private lateinit var swAutoSync: SwitchMaterial
    private lateinit var rvDevices: RecyclerView

    private val devices = mutableListOf<RemoteDevice>()
    private lateinit var adapter: DeviceAdapter
    private val exec = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_sync, container, false)
        tvDeviceId = v.findViewById(R.id.tvDeviceId)
        tvLog = v.findViewById(R.id.tvLog)
        swAutoSync = v.findViewById(R.id.swAutoSync)
        rvDevices = v.findViewById(R.id.rvDevices)

        adapter = DeviceAdapter(devices) { d -> restore(d) }
        rvDevices.layoutManager = LinearLayoutManager(requireContext())
        rvDevices.adapter = adapter

        val ctx = requireContext()
        val p = Prefs.get(ctx)
        tvDeviceId.text = "ID ${Store.deviceId(ctx)} · ${Store.deviceLabel(ctx)} · ${p.getString(K.OWNER, "kongbai9288")}/${p.getString(K.REPO, "")}"
        swAutoSync.isChecked = p.getBoolean(K.AUTO_SYNC, false)
        swAutoSync.setOnCheckedChangeListener { _, checked ->
            p.edit().putBoolean(K.AUTO_SYNC, checked).apply()
            SyncManager.schedule(ctx)
            toast(if (checked) "已开启自动同步" else "已关闭自动同步")
        }

        v.findViewById<Button>(R.id.btnUpload).setOnClickListener { upload() }
        v.findViewById<Button>(R.id.btnRefresh).setOnClickListener { refresh() }
        return v
    }

    private fun log(s: String) {
        safePost(handler) { tvLog.append("$s\n") }
    }

    private fun toast(s: String) {
        safePost(handler) { Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show() }
    }

    private fun bg(block: () -> Unit) {
        exec.execute {
            try {
                block()
            } catch (t: Throwable) {
                log("错误：${t.message}")
            }
        }
    }

    private fun upload() {
        val ctx = requireContext()
        log("开始上传…")
        bg {
            val msg = SyncManager.upload(ctx) { log(it) }
            log(msg)
            toast(msg)
        }
    }

    private fun refresh() {
        val ctx = requireContext()
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
}
