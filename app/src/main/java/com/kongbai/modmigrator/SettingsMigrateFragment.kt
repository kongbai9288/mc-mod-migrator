package com.kongbai.modmigrator

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Spinner
import androidx.fragment.app.Fragment
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsMigrateFragment : Fragment() {

    private lateinit var etDefVersion: EditText
    private lateinit var spDefLoader: Spinner
    private lateinit var swAutoInstall: SwitchMaterial
    private lateinit var swAutoLaunch: SwitchMaterial
    private lateinit var swAutoSync: SwitchMaterial
    private var loading = true

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_settings_migrate, container, false)
        etDefVersion = v.findViewById(R.id.etDefVersion)
        spDefLoader = v.findViewById(R.id.spDefLoader)
        swAutoInstall = v.findViewById(R.id.swAutoInstall)
        swAutoLaunch = v.findViewById(R.id.swAutoLaunch)
        swAutoSync = v.findViewById(R.id.swAutoSync)

        val p = Prefs.get(requireContext())
        etDefVersion.setText(p.getString(K.DEF_VERSION, "") ?: "")
        val arr = resources.getStringArray(R.array.loaders)
        val i = arr.indexOf(p.getString(K.DEF_LOADER, "auto") ?: "auto")
        if (i >= 0) spDefLoader.setSelection(i)
        swAutoInstall.isChecked = p.getBoolean(K.AUTO_INSTALL, true)
        swAutoLaunch.isChecked = p.getBoolean(K.AUTO_LAUNCH, false)
        swAutoSync.isChecked = p.getBoolean(K.AUTO_SYNC, false)

        etDefVersion.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!loading) Prefs.get(requireContext()).edit()
                    .putString(K.DEF_VERSION, etDefVersion.text.toString().trim()).apply()
            }
        })
        spDefLoader.setOnItemSelectedListenerSafe {
            if (!loading) Prefs.get(requireContext()).edit()
                .putString(K.DEF_LOADER, spDefLoader.selectedItem?.toString() ?: "auto").apply()
        }
        swAutoInstall.setOnCheckedChangeListener { _, c ->
            if (!loading) Prefs.get(requireContext()).edit().putBoolean(K.AUTO_INSTALL, c).apply()
        }
        swAutoLaunch.setOnCheckedChangeListener { _, c ->
            if (!loading) Prefs.get(requireContext()).edit().putBoolean(K.AUTO_LAUNCH, c).apply()
        }
        swAutoSync.setOnCheckedChangeListener { _, c ->
            if (!loading) {
                Prefs.get(requireContext()).edit().putBoolean(K.AUTO_SYNC, c).apply()
                SyncManager.schedule(requireContext())
            }
        }
        loading = false
        // 并发下载数
        val sp = v.findViewById<android.widget.Spinner>(R.id.spParallel)
        if (sp != null) {
            val labels = resources.getStringArray(R.array.parallel_labels)
            sp.adapter = android.widget.ArrayAdapter(
                requireContext(), R.layout.item_spinner, labels
            )
            val cur = Prefs.get(requireContext()).getInt(K.DOWNLOAD_PARALLEL, 3)
            val vals = resources.getStringArray(R.array.parallel_values)
            val idx = vals.indexOf(cur.toString())
            // Spinner 的 onItemSelected **在设置监听器时就会自动触发一次**，
            // 用户根本没操作。不设守卫的话，一进这个页面就会把并发数
            // 按当前选中项**重新写一遍**——而 setSelection 之前
            // 选中项是第 0 项（"1"），于是你设置里的 4 会被悄悄改成 1。
            var spLoading = true
            sp.setSelection(if (idx >= 0) idx else 2)
            sp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?, view2: android.view.View?,
                    pos: Int, id: Long
                ) {
                    if (spLoading) { spLoading = false; return }
                    val n = vals.getOrNull(pos)?.toIntOrNull() ?: 3
                    Prefs.get(requireContext()).edit().putInt(K.DOWNLOAD_PARALLEL, n).apply()
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }

        return v
    }
}
