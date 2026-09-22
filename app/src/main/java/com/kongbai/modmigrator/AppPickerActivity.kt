package com.kongbai.modmigrator

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/**
 * 启动器选择：列出系统里所有可启动应用，让用户自己认领哪个是 Minecraft 启动器。
 * 选好后保存包名，之后扫描与拉数据都按这个包名来。
 */
class AppPickerActivity : AppCompatActivity() {

    private val all = mutableListOf<LauncherHelper.AppInfo>()
    private val shown = mutableListOf<LauncherHelper.AppInfo>()
    private lateinit var adapter: AppAdapter
    private lateinit var etFilter: EditText
    private val exec = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)
        etFilter = findViewById(R.id.etFilter)
        val rv = findViewById<RecyclerView>(R.id.rvApps)
        adapter = AppAdapter(shown) { info -> choose(info) }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        etFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = filter(s?.toString() ?: "")
        })

        val cur = Prefs.get(this).getString(K.LAUNCHER, "") ?: ""
        if (cur.isNotBlank()) {
            Toast.makeText(this, "当前：$cur", Toast.LENGTH_SHORT).show()
        }
        load()
    }

    private fun load() {
        val ctx = this
        exec.execute {
            val list = LauncherHelper.listApps(ctx)
            handler.post {
                all.clear()
                all.addAll(list)
                filter(etFilter.text.toString())
                Toast.makeText(ctx, "共 ${list.size} 个应用，像启动器的已排在前面", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun filter(q: String) {
        val k = q.trim().lowercase()
        shown.clear()
        if (k.isBlank()) {
            shown.addAll(all)
        } else {
            shown.addAll(all.filter {
                it.label.lowercase().contains(k) || it.pkg.lowercase().contains(k)
            })
        }
        adapter.notifyDataSetChanged()
    }

    private fun choose(info: LauncherHelper.AppInfo) {
        Prefs.get(this).edit().putString(K.LAUNCHER, info.pkg).apply()
        Toast.makeText(this, "已选择：${info.label}\n接下来会按 ${info.pkg} 去拉数据", Toast.LENGTH_LONG).show()
        setResult(RESULT_OK)
        finish()
    }
}

class AppAdapter(
    private val items: List<LauncherHelper.AppInfo>,
    private val onPick: (LauncherHelper.AppInfo) -> Unit
) : RecyclerView.Adapter<AppAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.ivIcon)
        val name: TextView = v.findViewById(R.id.tvName)
        val meta: TextView = v.findViewById(R.id.tvMeta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val a = items[pos]
        h.name.text = a.label
        h.meta.text = a.pkg + if (a.hint) "  · 疑似启动器" else ""
        if (a.icon != null) h.icon.setImageDrawable(a.icon)
        else h.icon.setImageResource(R.drawable.ic_extension)
        h.itemView.setOnClickListener { onPick(a) }
    }
}
