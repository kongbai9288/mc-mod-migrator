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
        btnPick.setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            startActivityForResult(i, 21)
        }
        btnClear.setOnClickListener {
            Prefs.get(requireContext()).edit().putString(K.WORKDIR_URI, "").apply()
            refresh()
            Toast.makeText(requireContext(), "已清除工作目录", Toast.LENGTH_SHORT).show()
        }
        return v
    }

    private fun refresh() {
        val u = WorkDir.uri(requireContext())
        tvWork.text = if (u.isBlank()) "未设置：所有导出与缓存会放在应用私有目录" else "工作目录：$u"
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
