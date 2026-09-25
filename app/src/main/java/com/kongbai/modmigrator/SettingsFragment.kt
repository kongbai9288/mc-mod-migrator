package com.kongbai.modmigrator

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.concurrent.Executors

class SettingsFragment : Fragment() {

    private lateinit var etToken: EditText
    private lateinit var etOwner: EditText
    private lateinit var etRepo: EditText
    private lateinit var etBranch: EditText
    private lateinit var etCfKey: EditText
    private lateinit var etDefVersion: EditText
    private lateinit var spSource: Spinner
    private lateinit var spDefLoader: Spinner
    private lateinit var swAutoTrans: SwitchMaterial
    private lateinit var swMirror: SwitchMaterial
    private lateinit var swAutoInstall: SwitchMaterial
    private lateinit var swAutoLaunch: SwitchMaterial
    private lateinit var swAutoSync: SwitchMaterial
    private lateinit var tvConn: TextView
    private lateinit var tvLauncher: TextView

    private var loading = true
    private val exec = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_settings, container, false)
        etToken = v.findViewById(R.id.etToken)
        etOwner = v.findViewById(R.id.etOwner)
        etRepo = v.findViewById(R.id.etRepo)
        etBranch = v.findViewById(R.id.etBranch)
        etCfKey = v.findViewById(R.id.etCfKey)
        etDefVersion = v.findViewById(R.id.etDefVersion)
        spSource = v.findViewById(R.id.spSource)
        spDefLoader = v.findViewById(R.id.spDefLoader)
        swAutoTrans = v.findViewById(R.id.swAutoTrans)
        swMirror = v.findViewById(R.id.swMirror)
        swAutoInstall = v.findViewById(R.id.swAutoInstall)
        swAutoLaunch = v.findViewById(R.id.swAutoLaunch)
        swAutoSync = v.findViewById(R.id.swAutoSync)
        tvConn = v.findViewById(R.id.tvConn)
        tvLauncher = v.findViewById(R.id.tvLauncher)

        val p = Prefs.get(requireContext())
        etToken.setText(p.getString(K.TOKEN, "") ?: "")
        etOwner.setText(p.getString(K.OWNER, "kongbai9288") ?: "kongbai9288")
        etRepo.setText(p.getString(K.REPO, "mc-mod-migrator") ?: "mc-mod-migrator")
        etBranch.setText(p.getString(K.BRANCH, "main") ?: "main")
        etCfKey.setText(p.getString(K.CF_KEY, "") ?: "")
        etDefVersion.setText(p.getString(K.DEF_VERSION, "") ?: "")
        select(spSource, resources.getStringArray(R.array.sources), p.getString(K.SOURCE, "Modrinth") ?: "Modrinth")
        select(spDefLoader, resources.getStringArray(R.array.loaders), p.getString(K.DEF_LOADER, "auto") ?: "auto")
        swAutoTrans.isChecked = p.getBoolean(K.AUTO_TRANS, true)
        swMirror.isChecked = p.getBoolean(K.USE_MIRROR, true)
        swAutoInstall.isChecked = p.getBoolean(K.AUTO_INSTALL, true)
        swAutoLaunch.isChecked = p.getBoolean(K.AUTO_LAUNCH, false)
        swAutoSync.isChecked = p.getBoolean(K.AUTO_SYNC, false)
        tvLauncher.text = "启动器：${p.getString(K.LAUNCHER, "") ?: ""}"

        watch(etToken)
        watch(etOwner)
        watch(etRepo)
        watch(etBranch)
        watch(etCfKey)
        watch(etDefVersion)

        spSource.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!loading) saveAll()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spDefLoader.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!loading) saveAll()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        swAutoTrans.setOnCheckedChangeListener { _, c ->
            if (!loading) {
                Prefs.get(requireContext()).edit().putBoolean(K.AUTO_TRANS, c).apply()
            }
        }
        swMirror.setOnCheckedChangeListener { _, c ->
            if (!loading) {
                Prefs.get(requireContext()).edit().putBoolean(K.USE_MIRROR, c).apply()
            }
        }
        swAutoInstall.setOnCheckedChangeListener { _, c ->
            if (!loading) {
                Prefs.get(requireContext()).edit().putBoolean(K.AUTO_INSTALL, c).apply()
            }
        }
        swAutoLaunch.setOnCheckedChangeListener { _, c ->
            if (!loading) {
                Prefs.get(requireContext()).edit().putBoolean(K.AUTO_LAUNCH, c).apply()
            }
        }
        swAutoSync.setOnCheckedChangeListener { _, c ->
            if (!loading) {
                Prefs.get(requireContext()).edit().putBoolean(K.AUTO_SYNC, c).apply()
                SyncManager.schedule(requireContext())
            }
        }

        v.findViewById<Button>(R.id.btnTest).setOnClickListener { test() }
        v.findViewById<Button>(R.id.btnPickLauncher).setOnClickListener { pickLauncher() }
        v.findViewById<Button>(R.id.btnPrivacy).setOnClickListener { openInfo("privacy.txt", getString(R.string.privacy_title)) }
        v.findViewById<Button>(R.id.btnLicenses).setOnClickListener { openInfo("licenses.txt", getString(R.string.license_title)) }
        v.findViewById<Button>(R.id.btnCrash).setOnClickListener { showCrash() }
        v.findViewById<Button>(R.id.btnClear).setOnClickListener { clearData() }

        loading = false
        return v
    }

    private fun select(sp: Spinner, arr: Array<String>, value: String) {
        val i = arr.indexOf(value)
        if (i >= 0) sp.setSelection(i)
    }

    private fun watch(et: EditText) {
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!loading) saveAll()
            }
        })
    }

    private fun saveAll() {
        val p = Prefs.get(requireContext())
        p.edit()
            .putString(K.TOKEN, etToken.text.toString().trim())
            .putString(K.OWNER, etOwner.text.toString().trim())
            .putString(K.REPO, etRepo.text.toString().trim())
            .putString(K.BRANCH, etBranch.text.toString().trim().ifBlank { "main" })
            .putString(K.CF_KEY, etCfKey.text.toString().trim())
            .putString(K.DEF_VERSION, etDefVersion.text.toString().trim())
            .putString(K.SOURCE, spSource.selectedItem?.toString() ?: "Modrinth")
            .putString(K.DEF_LOADER, spDefLoader.selectedItem?.toString() ?: "auto")
            .apply()
    }

    private fun toast(s: String) {
        handler.post {
            if (!isAdded) return@post
            try {
                context?.let { android.widget.Toast.makeText(it, s, android.widget.Toast.LENGTH_SHORT).show() }
            } catch (t: Throwable) {
                // 界面已销毁，不弹
                     Err.ignore(t, "界面已销毁，不弹")
                 }
        }
    }

    private fun test() {
        saveAll()
        val ctx = requireContext()
        val c = SyncManager.creds(ctx)
        if (c == null) {
            tvConn.text = "请先填写 Token 和仓库名"
            return
        }
        tvConn.text = "连接中…"
        exec.execute {
            val result = try {
                val items = GitHubApi.listDir(c.owner, c.repo, "", c.branch, c.token)
                "连接成功，仓库根 ${items.size} 项"
            } catch (t: Throwable) {
                "连接失败：${t.message}"
            }
            safePost(handler) { tvConn.text = result }
        }
    }

    private fun pickLauncher() {
        // 不再猜包名：把系统应用列表列出来让用户自己认领
        startActivity(android.content.Intent(requireContext(), AppPickerActivity::class.java))
    }

    private fun openInfo(file: String, title: String) {
        val i = Intent(requireContext(), InfoActivity::class.java)
        i.putExtra("file", file)
        i.putExtra("title", title)
        startActivity(i)
    }

    private fun showCrash() {
        val ctx = requireContext()
        val f = java.io.File(ctx.filesDir, "crash.log")
        val txt = if (f.exists()) f.readText().take(6000) else "暂无崩溃记录"
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.cfg_crash_log)
            .setMessage(txt)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton("清空") { _, _ -> f.delete(); toast("已清空") }
            .show()
    }

    private fun clearData() {
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.cfg_clear)
            .setMessage("将清除已标记的下载链接与缓存，不会影响已配置目录。")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                Store.clear(ctx)
                val cache = ctx.cacheDir
                if (cache.exists()) cache.listFiles()?.forEach { it.deleteRecursively() }
                toast("已清除")
            }
            .show()
    }
    override fun onDestroyView() {
        super.onDestroyView()
        // 清理 Handler：页面销毁后若还有未执行的 post，
        // 回调里访问已销毁的 View 会直接崩。
        runCatching { handler.removeCallbacksAndMessages(null) }
    }

}
