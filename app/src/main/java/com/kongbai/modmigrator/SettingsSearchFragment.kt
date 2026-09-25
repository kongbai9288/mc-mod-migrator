package com.kongbai.modmigrator

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Spinner
import androidx.fragment.app.Fragment
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsSearchFragment : Fragment() {

    private lateinit var spSource: Spinner
    private lateinit var swBackend: SwitchMaterial
    private lateinit var swOfficialCf: SwitchMaterial
    private lateinit var etCfKey: EditText
    private lateinit var swAgg: SwitchMaterial
    private lateinit var swRec: SwitchMaterial
    private lateinit var swAutoTrans: SwitchMaterial
    private lateinit var swMirror: SwitchMaterial
    private var loading = true

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_settings_search, container, false)
        spSource = v.findViewById(R.id.spSource)
        swBackend = v.findViewById(R.id.swBackend)
        swOfficialCf = v.findViewById(R.id.swOfficialCf)
        etCfKey = v.findViewById(R.id.etCfKey)
        swAgg = v.findViewById(R.id.swAgg)
        swRec = v.findViewById(R.id.swRec)
        swAutoTrans = v.findViewById(R.id.swAutoTrans)
        swMirror = v.findViewById(R.id.swMirror)

        val p = Prefs.get(requireContext())
        val arr = resources.getStringArray(R.array.sources2)
        val cur = p.getString(K.SOURCE, "聚合") ?: "聚合"
        val i = arr.indexOf(cur)
        if (i >= 0) spSource.setSelection(i)

        swBackend.isChecked = p.getBoolean(K.USE_BACKEND, true)
        swOfficialCf.isChecked = p.getBoolean(K.USE_OFFICIAL_CF, false)
        etCfKey.setText(p.getString(K.CF_KEY, "") ?: "")
        etCfKey.addTextWatcherSafe {
            Prefs.get(requireContext()).edit().putString(K.CF_KEY, etCfKey.text.toString().trim()).apply()
        }
        // 互斥：开一个自动关另一个
        swBackend.setOnCheckedChangeListener { _, c ->
            if (loading) return@setOnCheckedChangeListener
            Prefs.get(requireContext()).edit().putBoolean(K.USE_BACKEND, c).apply()
            if (c && swOfficialCf.isChecked) {
                loading = true
                swOfficialCf.isChecked = false
                loading = false
                Prefs.get(requireContext()).edit().putBoolean(K.USE_OFFICIAL_CF, false).apply()
            }
        }
        swOfficialCf.setOnCheckedChangeListener { _, c ->
            if (loading) return@setOnCheckedChangeListener
            Prefs.get(requireContext()).edit().putBoolean(K.USE_OFFICIAL_CF, c).apply()
            if (c && swBackend.isChecked) {
                loading = true
                swBackend.isChecked = false
                loading = false
                Prefs.get(requireContext()).edit().putBoolean(K.USE_BACKEND, false).apply()
            }
        }
        swAgg.isChecked = p.getBoolean(K.AGG_SEARCH, true)
        swRec.isChecked = p.getBoolean(K.RECOMMEND, true)
        swAutoTrans.isChecked = p.getBoolean(K.AUTO_TRANS, true)
        swMirror.isChecked = p.getBoolean(K.USE_MIRROR, true)

        // Spinner 的 onItemSelected 在**设置监听器时就会自动触发一次**
        // （布局完成即回调，用户根本没操作）。
        // 不加 loading 守卫的话，一进这个页面就立刻执行一次 save()，
        // 把还没初始化完的状态写进配置 —— 同页面其他开关都有 `if (!loading)` 守卫，
        // 唯独这里漏了。
        spSource.setOnItemSelectedListenerSafe { if (!loading) save() }
        swAgg.setOnCheckedChangeListener { _, c -> if (!loading) { Prefs.get(requireContext()).edit().putBoolean(K.AGG_SEARCH, c).apply() } }
        swRec.setOnCheckedChangeListener { _, c -> if (!loading) { Prefs.get(requireContext()).edit().putBoolean(K.RECOMMEND, c).apply() } }
        swAutoTrans.setOnCheckedChangeListener { _, c -> if (!loading) { Prefs.get(requireContext()).edit().putBoolean(K.AUTO_TRANS, c).apply() } }
        swMirror.setOnCheckedChangeListener { _, c -> if (!loading) { Prefs.get(requireContext()).edit().putBoolean(K.USE_MIRROR, c).apply() } }

        loading = false
        return v
    }

    private fun save() {
        Prefs.get(requireContext()).edit()
            .putString(K.SOURCE, spSource.selectedItem?.toString() ?: "聚合")
            .apply()
    }
}
