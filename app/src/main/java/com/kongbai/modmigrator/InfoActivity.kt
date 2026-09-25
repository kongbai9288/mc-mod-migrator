package com.kongbai.modmigrator

import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class InfoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须在 super 之前 setTheme，否则这个页面用的是 Manifest 里的默认主题，
        // 用户在设置里选的配色**在这里不生效** —— 表现为「有些页面不跟主题色」。
        setTheme(ThemePrefs.styleRes(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_info)

        val file = intent.getStringExtra("file") ?: "privacy.txt"
        val title = intent.getStringExtra("title") ?: getString(R.string.privacy_title)

        findViewById<TextView>(R.id.tvTitle).text = title
        val text = try {
            assets.open(file).readBytes().toString(Charsets.UTF_8)
        } catch (t: Throwable) {
            "（内容缺失：$file）"
        }
        findViewById<TextView>(R.id.tvInfo).text = text
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
    }
}
