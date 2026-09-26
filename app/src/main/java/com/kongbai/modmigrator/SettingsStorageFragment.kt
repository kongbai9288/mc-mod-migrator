package com.kongbai.modmigrator

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment

class SettingsStorageFragment : Fragment() {

    private lateinit var tvWork: TextView
    private lateinit var btnPick: Button
    private lateinit var btnClear: Button

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_settings_storage, container, false)
        tvWork = v.findViewById(R.id.tvWork)
        btnPick = v.findViewById(R.id.btnPickWork)
        btnClear = v.findViewById(R.id.btnClearWork)

        refresh()
        setupBackup(v)
        btnPick.setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            startActivityForResult(i, 21)
        }
        btnClear.setOnClickListener {
            Prefs.get(requireContext()).edit().putString(K.WORKDIR_URI, "").apply()
            WorkDir.invalidate()
            refresh()
            Toast.makeText(requireContext(), "已清除工作目录", Toast.LENGTH_SHORT).show()
        }
        return v
    }

    private fun refresh() {
        val u = WorkDir.uri(requireContext())
        tvWork.text = if (u.isBlank()) "未设置：所有导出与缓存会放在应用私有目录" else "工作目录：$u"
    }

    /** 云盘备份：上传地址 + 自动备份间隔 */
    private fun setupBackup(v: View) {
        val ctx = requireContext()
        val etUrl = v.findViewById<android.widget.EditText>(R.id.etCloudUrl)
        val sp = v.findViewById<android.widget.Spinner>(R.id.spBackup)
        val tv = v.findViewById<TextView>(R.id.tvBackup)
        val swIcon = v.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.swLocalIcon)

        etUrl?.setText(CloudBackup.uploadUrl(ctx))
        etUrl?.setOnFocusChangeListener { _, has ->
            if (!has) CloudBackup.setUploadUrl(ctx, etUrl.text.toString())
        }

        swIcon?.isChecked = Prefs.get(ctx).getBoolean(K.LOCAL_ICON, true)
        swIcon?.setOnCheckedChangeListener { _, c ->
            Prefs.get(ctx).edit().putBoolean(K.LOCAL_ICON, c).apply()
        }

        if (sp != null) {
            val labels = CloudBackup.INTERVALS.map { it.second }
            sp.adapter = android.widget.ArrayAdapter(
                ctx, R.layout.item_spinner, labels
            )
            val cur = CloudBackup.intervalHours(ctx)
            val idx = CloudBackup.INTERVALS.indexOfFirst { it.first == cur }
            // Spinner 的 onItemSelected **在设置监听器时就会自动触发一次**，
            // 用户根本没操作。不设守卫的话，每次进这个页面都会执行一次
            // setIntervalHours → schedule（注册周期任务）。
            // 本页就是"一进设置就白跑一次"，这里按其他设置页同样加守卫。
            var spinLoading = true
            sp.setSelection(if (idx >= 0) idx else 3)
            sp.onItemSelectedListener =
                object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        p: android.widget.AdapterView<*>?, vv: View?, pos: Int, id: Long
                    ) {
                        if (spinLoading) { spinLoading = false; return }
                        val h = CloudBackup.INTERVALS.getOrNull(pos)?.first ?: 24
                        CloudBackup.setIntervalHours(ctx, h)
                        if (tv != null) tv.text = CloudBackup.describe(ctx)
                    }
                    override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
                }
        }

        v.findViewById<Button>(R.id.btnOpenCloud)?.setOnClickListener {
            val u = CloudBackup.uploadUrl(ctx)
            if (u.isBlank()) {
                Toast.makeText(ctx, "先填上传地址，或直接用内置浏览器打开你的云盘", Toast.LENGTH_SHORT).show()
            } else {
                WebActivity.open(ctx, u, "云盘")
            }
        }

        v.findViewById<Button>(R.id.btnBackupNow)?.setOnClickListener {
            // ⚠️ 之前直接读 `CloudBackup.uploadUrl(ctx)`（已存的配置）。
            // 但地址框只在**失去焦点**时才保存——用户填完地址直接点「立即备份」，
            // 焦点可能还没丢，配置里仍是空的 → 弹"请先填写地址"，
            // 用户明明刚填完，看着像功能坏了。
            // 这里先把输入框的内容落盘再读，避免依赖焦点时序。
            val typed = etUrl?.text?.toString()?.trim() ?: ""
            CloudBackup.setUploadUrl(ctx, typed)
            val u = CloudBackup.uploadUrl(ctx)
            if (u.isBlank()) {
                Toast.makeText(ctx, "请先填写云盘上传地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Thread {
                try {
                    // 和 CloudBackupWorker 同一个毛病：不看成败就更新时间戳，
                    // 失败也说"备份完成"。这里改成如实反馈。
                    val msg = SyncManager.upload(ctx) { }
                    val done = CloudBackup.ok(msg)
                    if (done) CloudBackup.markBackedUp(ctx)
                    main {
                        tv?.text = CloudBackup.describe(ctx)
                        Toast.makeText(
                            ctx,
                            if (done) "备份完成：$msg" else "备份失败：$msg",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (t: Throwable) {
                    main { Toast.makeText(ctx, "备份失败：${t.message}", Toast.LENGTH_LONG).show() }
                }
            }.start()
        }

        if (tv != null) tv.text = CloudBackup.describe(ctx)
    }

    private fun main(b: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { b() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 21 && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            try {
                // persist() 返回的是**是否真的拿到了持久化授权**。
                // 之前这里忽略返回值、一律弹"工作目录已设置"——
                // 而 take() 失败（配额触顶、ROM 限制等）时 URI 照样被存了，
                // 于是界面显示"已授权外部目录"，实际重启后读不了，
                // 导出/下载全落空。用户完全不知道是授权没成功。
                val ok = WorkDir.persist(requireContext(), uri)
                refresh()
                Toast.makeText(
                    requireContext(),
                    if (ok) "工作目录已设置"
                    else "目录已选中，但持久化授权未成功（重启后可能失效）",
                    Toast.LENGTH_LONG
                ).show()
            } catch (t: Throwable) {
                Toast.makeText(requireContext(), "授权失败：${t.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
