package com.kongbai.modmigrator

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.util.concurrent.Executors

class DevsFragment : Fragment() {

    private val exec = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_devs, container, false)
        val box = v.findViewById<LinearLayout>(R.id.boxDevs)
        val tv = v.findViewById<TextView>(R.id.tvDevsState)
        val ctx = requireContext()
        tv.text = "正在读取名单…"
        exec.execute {
            try {
                val list = DevTeam.load(ctx)
                safePost(handler) {
                    box.removeAllViews()
                    for (d in list) {
                        val row = TextView(ctx)
                        row.text = "${d.name} · ${d.role}"
                        row.textSize = 14f
                        row.setPadding(0, 10, 0, 10)
                        if (d.url.isNotBlank()) {
                            row.setOnClickListener {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(d.url)))
                            }
                        }
                        box.addView(row)
                    }
                    tv.text = "共 ${list.size} 位（点击可打开主页）"
                }

            } catch (t: Throwable) {
                // 后台异常不崩进程
            }
        }
        return v
    }
}
