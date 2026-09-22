package com.kongbai.modmigrator

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Spinner
import androidx.fragment.app.Fragment
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsSearchFragment : Fragment() {

    private lateinit var spSource: Spinner
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
        swAgg = v.findViewById(R.id.swAgg)
        swRec = v.findViewById(R.id.swRec)
        swAutoTrans = v.findViewById(R.id.swAutoTrans)
        swMirror = v.findViewById(R.id.swMirror)

        val p = Prefs.get(requireContext())
        val arr = resources.getStringArray(R.array.sources2)
        val cur = p.getString(K.SOURCE, "聚合") ?: "聚合"
        val i = arr.indexOf(cur)
        if (i >= 0) spSource.setSelection(i)

        swAgg.isChecked = p.getBoolean(K.AGG_SEARCH, true)
        swRec.isChecked = p.getBoolean(K.RECOMMEND, true)
        swAutoTrans.isChecked = p.getBoolean(K.AUTO_TRANS, true)
        swMirror.isChecked = p.getBoolean(K.USE_MIRROR, true)

        spSource.setOnItemSelectedListenerSafe { save() }
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
