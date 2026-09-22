package com.kongbai.modmigrator

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import java.io.File

class SettingsAboutFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_settings_about, container, false)
        v.findViewById<Button>(R.id.btnPrivacy).setOnClickListener {
            openInfo("privacy.txt", getString(R.string.privacy_title))
        }
        v.findViewById<Button>(R.id.btnLicenses).setOnClickListener {
            openInfo("licenses.txt", getString(R.string.license_title))
        }
        v.findViewById<Button>(R.id.btnCrash).setOnClickListener { showCrash() }
        v.findViewById<Button>(R.id.btnClear).setOnClickListener {
            Store.clear(requireContext())
            val c = requireContext().cacheDir
            if (c.exists()) c.listFiles()?.forEach { it.deleteRecursively() }
        }
        return v
    }

    private fun openInfo(file: String, title: String) {
        val i = Intent(requireContext(), InfoActivity::class.java)
        i.putExtra("file", file)
        i.putExtra("title", title)
        startActivity(i)
    }

    private fun showCrash() {
        val f = File(requireContext().filesDir, "crash.log")
        val txt = if (f.exists()) f.readText().take(6000) else "暂无崩溃记录"
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.cfg_crash_log)
            .setMessage(txt)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton("清空") { _, _ -> f.delete() }
            .show()
    }
}
