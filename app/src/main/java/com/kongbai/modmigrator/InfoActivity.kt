package com.kongbai.modmigrator

import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class InfoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
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
