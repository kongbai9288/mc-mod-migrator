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
            Prefs.get(requireContext()).edit().putString(K.WORKDIR_URI, "")
                    WorkDir.invalidate().apply()
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
            sp.setSelection(if (idx >= 0) idx else 3)
            sp.onItemSelectedListener =
                object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        p: android.widget.AdapterView<*>?, vv: View?, pos: Int, id: Long
                    ) {
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
            val u = CloudBackup.uploadUrl(ctx)
            if (u.isBlank()) {
                Toast.makeText(ctx, "请先填写云盘上传地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Thread {
                try {
                    SyncManager.upload(ctx) { }
                    CloudBackup.markBackedUp(ctx)
                    main { tv?.text = CloudBackup.describe(ctx); Toast.makeText(ctx, "备份完成", Toast.LENGTH_SHORT).show() }
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
                WorkDir.persist(requireContext(), uri)
                refresh()
                Toast.makeText(requireContext(), "工作目录已设置", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Toast.makeText(requireContext(), "授权失败：${t.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
