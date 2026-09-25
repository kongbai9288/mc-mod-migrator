package com.kongbai.modmigrator

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * 内置文本编辑器：直接改模组配置文件。
 *
 * 支持 .toml / .cfg / .properties / .json / .yaml / .txt 等纯文本配置。
 * 改完保存到工作目录，迁移时可勾选带走。
 */
class EditorActivity : AppCompatActivity() {

    private lateinit var et: EditText
    private var path = ""
    private var originUri: String? = null
    private var dirty = false
    private var original = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        et = findViewById(R.id.etContent)
        path = intent.getStringExtra("path") ?: ""
        originUri = intent.getStringExtra("uri")

        // 标题优先用传入的显示名，没有再退回文件名。
        // 从 SAF 进来的只有 uri（拿不到真实路径），
        // 不这样处理标题会一直是"编辑器"，用户不知道在编辑哪个文件。
        val displayName = intent.getStringExtra("name") ?: ""
        title = when {
            displayName.isNotBlank() -> displayName
            path.isNotBlank() -> File(path).name
            else -> "编辑器"
        }

        val text = loadText()
        original = text
        et.setText(text)
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                dirty = true
            }
        })

        if (path.isBlank()) {
            Toast.makeText(this, "没有指定文件", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadText(): String {
        val uri = originUri
        if (!uri.isNullOrBlank()) {
            return try {
                val df = androidx.documentfile.provider.DocumentFile.fromSingleUri(
                    this, android.net.Uri.parse(uri)
                )
                // df 为空时安静返回空串，不能 !!（会 NPE 直接崩）
                if (df == null) "" else
                    contentResolver.openInputStream(df.uri)
                        ?.bufferedReader()?.readText() ?: ""
            } catch (t: Throwable) {
                "读取失败：${t.message}"
            }
        }
        if (path.isNotBlank()) {
            return try {
                File(path).readText()
            } catch (t: Throwable) {
                "读取失败：${t.message}"
            }
        }
        return ""
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "保存")
        menu.add(0, 2, 0, "保存到配置备份")
        menu.add(0, 3, 0, "还原")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> save(false)
            2 -> save(true)
            3 -> {
                et.setText(original)
                dirty = false
                Toast.makeText(this, "已还原", Toast.LENGTH_SHORT).show()
            }
        }
        return true
    }

    private fun save(toBackup: Boolean) {
        val name = if (path.isBlank()) "config.txt" else File(path).name
        if (toBackup) {
            val dir = WorkDir.configs(this)
            if (dir == null) {
                Toast.makeText(this, "请先在工作目录页授权目录", Toast.LENGTH_SHORT).show()
                return
            }
            val exists = dir.findFile(name)
            val target = exists ?: dir.createFile("text/plain", name)
            if (target == null) {
                Toast.makeText(this, "创建文件失败", Toast.LENGTH_SHORT).show()
                return
            }
            writeTo(target.uri, name)
            return
        }
        if (path.isNotBlank() && File(path).exists()) {
            try {
                File(path).writeText(et.text.toString())
                original = et.text.toString()
                dirty = false
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
                return
            } catch (t: Throwable) {
                Toast.makeText(this, "保存失败：${t.message}", Toast.LENGTH_SHORT).show()
                return
            }
        }
        val uri = originUri
        if (!uri.isNullOrBlank()) {
            writeTo(android.net.Uri.parse(uri), name)
            return
        }
        Toast.makeText(this, "没有可写的位置", Toast.LENGTH_SHORT).show()
    }

    private fun writeTo(uri: android.net.Uri, name: String) {
        try {
            contentResolver.openOutputStream(uri, "wt")?.use {
                it.write(et.text.toString().toByteArray())
            }
            original = et.text.toString()
            dirty = false
            Toast.makeText(this, "已保存 $name", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Toast.makeText(this, "保存失败：${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onBackPressed() {
        if (dirty) {
            AlertDialog.Builder(this)
                .setTitle("有未保存的修改")
                .setMessage("要保存吗？")
                .setPositiveButton("保存") { _, _ -> save(false); finish() }
                .setNegativeButton("放弃") { _, _ -> finish() }
                .setNeutralButton("取消", null)
                .show()
        } else {
            super.onBackPressed()
        }
    }
    companion object {
        /**
         * 打开编辑器。
         *
         * 之前这个类**完全没有入口**——功能写好了但用户点不到，等于空页面。
         * 现在提供统一的启动方法，模组管理页的"编辑配置"会调它。
         */
        fun open(ctx: android.content.Context, uri: android.net.Uri, name: String) {
            val i = android.content.Intent(ctx, EditorActivity::class.java)
            i.putExtra("uri", uri.toString())
            i.putExtra("name", name)
            if (ctx !is android.app.Activity) i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
    }

}
