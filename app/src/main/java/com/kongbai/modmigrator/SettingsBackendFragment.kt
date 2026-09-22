package com.kongbai.modmigrator

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import java.util.concurrent.Executors

class SettingsBackendFragment : Fragment() {

    private lateinit var etBase: EditText
    private lateinit var etBackup: EditText
    private lateinit var btnProbe: Button
    private lateinit var tv: TextView
    private val exec = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_settings_backend, container, false)
        etBase = v.findViewById(R.id.etBase)
        etBackup = v.findViewById(R.id.etBackup)
        btnProbe = v.findViewById(R.id.btnProbe)
        tv = v.findViewById(R.id.tvState)

        val p = Prefs.get(requireContext())
        etBase.setText(p.getString(K.BACKEND_BASE, "") ?: "")
        etBackup.setText(p.getString(K.BACKEND_BACKUP, "") ?: "")

        fun watch(et: EditText) = et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                Prefs.get(requireContext()).edit()
                    .putString(K.BACKEND_BASE, etBase.text.toString().trim())
                    .putString(K.BACKEND_BACKUP, etBackup.text.toString().trim())
                    .apply()
            }
        })
        watch(etBase)
        watch(etBackup)

        btnProbe.setOnClickListener { probe() }
        probe()
        return v
    }

    private fun probe() {
        tv.text = "正在检测…"
        val ctx = requireContext()
        exec.execute {
            try {
                val r = BackendApi.probe(ctx)
                safePost(handler) {
                    tv.text = r.second
                    Toast.makeText(ctx, if (r.first) "后端可用" else "后端不可用", Toast.LENGTH_SHORT).show()
                }

            } catch (t: Throwable) {
                // 后台异常不崩进程
            }
        }
    }
}
