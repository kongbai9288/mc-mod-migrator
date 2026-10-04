package com.kongbai.modmigrator

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.opentransfer.openlist.OpenListClient
import com.opentransfer.openlist.OpenListConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 云盘上传 / 下载。
 *
 * 底层用的是 OpenList（AList 的社区分支）的 Android SDK：
 * 一个 SDK 覆盖 20+ 种后端 —— 阿里云盘、百度网盘、夸克、天翼、123、
 * 115、蓝奏，以及 Google Drive / OneDrive / Dropbox / S3 / OSS / COS /
 * WebDAV / FTP / SFTP。
 *
 * 之所以选它而不是各家自己的 SDK：
 *   · 不用绑定某一家，你用哪家就配哪家
 *   · 各家 SDK 都要单独申请 key，而这个只需要网盘自己的凭据
 *   · 接口统一，新增一家不用改调用方
 *
 * 凭据只存在本机（加密 SharedPreferences），不经本应用转发到任何第三方。
 */
class CloudDriveActivity : AppCompatActivity() {

    companion object {
        fun open(ctx: Context) {
            ctx.startActivity(android.content.Intent(ctx, CloudDriveActivity::class.java))
        }

        private const val K_CFG = "cloud_cfg"

        /** 可选后端：key（内部标识）、显示名、需要用户填的字段 */
        private val DRIVES = listOf(
            Triple("aliyun", "阿里云盘", listOf("refreshToken")),
            Triple("baidu", "百度网盘", listOf("accessToken")),
            Triple("quark", "夸克网盘", listOf("cookie")),
            Triple("tianyi", "天翼云盘", listOf("accessToken")),
            Triple("pan123", "123 网盘", listOf("token")),
            Triple("cloud115", "115 网盘", listOf("cookie")),
            Triple("googledrive", "Google Drive", listOf("refreshToken", "clientId", "clientSecret")),
            Triple("onedrive", "OneDrive", listOf("refreshToken", "clientId", "clientSecret")),
            Triple("webdav", "WebDAV", listOf("url", "username", "password")),
        )
    }

    private lateinit var tvState: TextView
    private lateinit var box: LinearLayout
    private var client: OpenListClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "云盘"

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        scroll.addView(root)
        setContentView(
            scroll, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        root.addView(UiCards.hint(this, "接哪家网盘由你决定，凭据只存在本机。"))

        tvState = TextView(this).apply { textSize = 12f }
        root.addView(tvState)

        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(box)

        root.addView(MaterialButton(this).apply {
            text = "选择并连接网盘"
            setOnClickListener { pickDrive() }
        })

        // 已存过配置就直接接上，省得每次进都重填一遍
        val saved = Prefs.get(this).getString(K_CFG, null)
        if (!saved.isNullOrBlank()) {
            runCatching { client = OpenListClient.fromJson(saved) }
                .onFailure { Err.ignore(it, "恢复网盘配置") }
        }
        refresh()
    }

    private fun refresh() {
        val c = client
        if (c == null) {
            tvState.text = "还没连接网盘"
            box.removeAllViews()
            return
        }
        tvState.text = "已配置：${c.provider}　（点「测试连接」确认可用）"
        box.removeAllViews()
        box.addView(MaterialButton(this).apply {
            text = "测试连接"
            setOnClickListener { test() }
        })
        box.addView(MaterialButton(this).apply {
            text = "浏览文件"
            setOnClickListener { browse() }
        })
        box.addView(MaterialButton(this).apply {
            text = "上传一个文件"
            setOnClickListener { pickAndUpload() }
        })
        box.addView(MaterialButton(this).apply {
            text = "断开"
            setOnClickListener {
                Prefs.get(this@CloudDriveActivity).edit().remove(K_CFG).apply()
                client = null
                refresh()
            }
        })
    }

    // ------------------------------------------------------------------

    private fun pickDrive() {
        val labels = DRIVES.map { it.second }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("用哪个网盘")
            .setItems(labels) { _, i ->
                val (key, _, fields) = DRIVES[i]
                askFields(key, fields)
            }
            .show()
    }

    private fun askFields(key: String, fields: List<String>) {
        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
        }
        val eds = ArrayList<TextInputEditText>()
        for (f in fields) {
            root.addView(TextView(ctx).apply { text = f })
            root.addView(TextInputEditText(ctx).apply {
                hint = f
                setSingleLine(true)
            }.also { eds.add(it) })
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("填写凭据")
            .setView(root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton("连接") { _, _ ->
                val vals = eds.map { it.text?.toString()?.trim() ?: "" }
                if (vals.any { it.isBlank() }) {
                    toast("还有没填的")
                    return@setPositiveButton
                }
                val cfg = runCatching { buildConfig(key, vals) }
                    .onFailure { Err.fail(it, "构造网盘配置") }
                    .getOrNull() ?: run { toast("配置构造失败"); return@setPositiveButton }
                val json = runCatching { cfg.toJson() }
                    .onFailure { Err.fail(it, "序列化网盘配置") }
                    .getOrNull() ?: run { toast("配置保存失败"); return@setPositiveButton }
                Prefs.get(ctx).edit().putString(K_CFG, json).apply()
                client = runCatching { OpenListClient.fromJson(json) }
                    .onFailure { Err.fail(it, "创建网盘客户端") }
                    .getOrNull()
                refresh()
            }
            .show()
    }

    private fun buildConfig(key: String, v: List<String>): OpenListConfig = when (key) {
        "aliyun" -> OpenListConfig.aliyunDrive(v[0])
        "baidu" -> OpenListConfig.baiduNetdisk(v[0])
        "quark" -> OpenListConfig.quark(v[0])
        "tianyi" -> OpenListConfig.tianyiCloud(v[0])
        "pan123" -> OpenListConfig.pan123(v[0])
        "cloud115" -> OpenListConfig.cloud115(v[0])
        "googledrive" -> OpenListConfig.googleDrive(v[0], v[1], v[2])
        "onedrive" -> OpenListConfig.oneDrive(v[0], v[1], v[2])
        "webdav" -> OpenListConfig.webDav(v[0], v[1], v[2])
        else -> throw IllegalArgumentException("不支持的网盘：$key")
    }

    // ------------------------------------------------------------------

    private fun test() {
        val c = client ?: return
        tvState.text = "正在测试…"
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { c.testConnection() }
                    .getOrNull()
            }
            if (r == null) {
                tvState.text = "测试失败（SDK 未就绪）"
                return@launch
            }
            tvState.text = when (r) {
                is com.opentransfer.openlist.OpenListResult.Success -> "连接成功 ✓"
                is com.opentransfer.openlist.OpenListResult.Failure ->
                    "连接失败：${r.error.message ?: r.error.javaClass.simpleName}"
            }
        }
    }

    private fun browse() {
        val c = client ?: return
        tvState.text = "正在读取…"
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { c.list("/") }.getOrNull()
            }
            when {
                r == null -> tvState.text = "读取失败（SDK 未就绪）"
                r is com.opentransfer.openlist.OpenListResult.Failure ->
                    tvState.text = "读取失败：${r.error.message ?: ""}"
                r is com.opentransfer.openlist.OpenListResult.Success -> {
                    val files = r.data
                    tvState.text = "根目录 ${files.size} 项"
                    val names = files.map {
                        "${if (it.isDirectory) "[目录] " else ""}${it.name}"
                    }.toTypedArray()
                    MaterialAlertDialogBuilder(this@CloudDriveActivity)
                        .setTitle("根目录（${files.size} 项）")
                        .setItems(names, null)
                        .show()
                }
            }
        }
    }

    private fun pickAndUpload() {
        val i = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        runCatching { startActivityForResult(i, 91) }
            .onFailure { toast("打不开文件选择器") }
    }

    @Deprecated("legacy")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 91 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val c = client ?: return
        val name = displayName(uri) ?: "upload.bin"
        tvState.text = "正在上传…"
        lifecycleScope.launch {
            val msg = withContext(Dispatchers.IO) {
                val tmp = java.io.File(cacheDir, "up_$name")
                runCatching {
                    contentResolver.openInputStream(uri)?.use { inp ->
                        tmp.outputStream().use { out -> inp.copyTo(out) }
                    }
                    val r = c.uploadWithMkdir(tmp, "/ModMigrator/$name")
                    when (r) {
                        is com.opentransfer.openlist.OpenListResult.Success -> "上传成功：/ModMigrator/$name"
                        is com.opentransfer.openlist.OpenListResult.Failure ->
                            "上传失败：${r.error.message ?: ""}"
                    }
                }.getOrElse { "上传失败：${it.message ?: it.javaClass.simpleName}" }
            }
            tvState.text = msg
            toast(msg)
        }
    }

    /** 从 SAF URI 取一个能用的文件名 */
    private fun displayName(uri: android.net.Uri): String? {
        return runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0) c.getString(i) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
