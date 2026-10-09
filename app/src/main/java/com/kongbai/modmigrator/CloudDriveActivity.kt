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

        /**
         * 一个要填的字段。
         * label 是给用户看的中文名，hint 是示例，def 是留空时的默认值。
         * 之前直接把 refreshToken / clientSecret 这种内部字段名摆给用户看，
         * 普通用户根本不知道那是什么、更不知道去哪拿。
         */
        private data class Fld(
            val label: String,
            val hint: String,
            val def: String = "",
            val pwd: Boolean = false,
        )

        /**
         * 一家网盘。
         * how 是一句人话说明，helpUrl 是「去哪拿」一键打开的页面。
         */
        private data class Drive(
            val key: String,
            val name: String,
            val fields: List<Fld>,
            /** 普通用户填得出来的（账号密码类），排前面并标注推荐 */
            val easy: Boolean = false,
            val how: String = "",
            val helpUrl: String = "",
        )

        private val DRIVES = listOf(
            Drive(
                "webdav", "WebDAV（推荐）",
                listOf(
                    Fld("服务器地址", "https://example.com/dav"),
                    Fld("用户名", "你的账号"),
                    Fld("密码", "", pwd = true),
                ),
                easy = true,
                how = "只要地址 + 账号 + 密码。NAS、自建服务器、alist 都支持，不需要任何令牌。"
            ),
            Drive(
                "ftp", "FTP（推荐）",
                listOf(
                    Fld("主机", "192.168.1.10"),
                    Fld("端口", "21", def = "21"),
                    Fld("用户名", "你的账号"),
                    Fld("密码", "", pwd = true),
                ),
                easy = true,
                how = "普通账号密码就能连，和服务器页里那个 FTP 是一回事。"
            ),
            Drive(
                "sftp", "SFTP（推荐）",
                listOf(
                    Fld("主机", "192.168.1.10"),
                    Fld("端口", "22", def = "22"),
                    Fld("用户名", "你的账号"),
                    Fld("密码", "", pwd = true),
                ),
                easy = true,
                how = "加密版的 FTP，同样是账号密码。"
            ),
            Drive(
                "lanzou", "蓝奏云（推荐）",
                listOf(
                    Fld("用户名", "手机号或账号"),
                    Fld("密码", "", pwd = true),
                ),
                easy = true,
                how = "直接填你在蓝奏云的账号密码。"
            ),
            Drive(
                "aliyun", "阿里云盘",
                listOf(Fld("Refresh Token（刷新令牌）", "从网页版后台取得的一长串字符")),
                how = "这个令牌不是你的登录密码，要从阿里云盘网页版后台取。点「去哪拿」会打开取令牌的页面。",
                helpUrl = "https://alist.nn.ci/zh/guide/drivers/aliyundrive_open.html"
            ),
            Drive(
                "baidu", "百度网盘",
                listOf(Fld("Access Token（访问令牌）", "从百度开放平台取得")),
                how = "需要在百度开放平台申请应用后才能拿到，过程比较麻烦。",
                helpUrl = "https://alist.nn.ci/zh/guide/drivers/baidu.html"
            ),
            Drive(
                "quark", "夸克网盘",
                listOf(Fld("Cookie", "登录后从浏览器复制的一整串")),
                how = "要在电脑上登录夸克网盘网页版，再从浏览器里复制 Cookie。",
                helpUrl = "https://alist.nn.ci/zh/guide/drivers/quark.html"
            ),
            Drive(
                "tianyi", "天翼云盘",
                listOf(Fld("Access Token（访问令牌）", "从天翼云盘取得")),
                how = "需要从天翼云盘网页端取得，同样不是登录密码。",
                helpUrl = "https://alist.nn.ci/zh/guide/drivers/tianyi.html"
            ),
            Drive(
                "pan123", "123 网盘",
                listOf(Fld("Token（令牌）", "从 123 网盘取得")),
                how = "需要在 123 网盘的授权页面取得。",
                helpUrl = "https://alist.nn.ci/zh/guide/drivers/123.html"
            ),
            Drive(
                "cloud115", "115 网盘",
                listOf(Fld("Cookie", "登录后从浏览器复制的一整串")),
                how = "要在电脑上登录 115 网页版，再从浏览器里复制 Cookie。",
                helpUrl = "https://alist.nn.ci/zh/guide/drivers/115.html"
            ),
            Drive(
                "googledrive", "Google Drive",
                listOf(
                    Fld("Refresh Token", "刷新令牌"),
                    Fld("Client ID", "客户端 ID"),
                    Fld("Client Secret", "客户端密钥", pwd = true),
                ),
                how = "要在 Google Cloud 建项目、开 API、建凭据，三步之后才有这三个值。",
                helpUrl = "https://alist.nn.ci/zh/guide/drivers/gd.html"
            ),
            Drive(
                "onedrive", "OneDrive",
                listOf(
                    Fld("Refresh Token", "刷新令牌"),
                    Fld("Client ID", "客户端 ID"),
                    Fld("Client Secret", "客户端密钥", pwd = true),
                ),
                how = "要在 Azure 注册应用才能拿到，步骤较多。",
                helpUrl = "https://alist.nn.ci/zh/guide/drivers/onedrive.html"
            ),
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
        // 账号密码类的排前面，令牌类的排后面并标出「需要令牌」
        val ordered = DRIVES.sortedByDescending { it.easy }
        val labels = ordered.map {
            if (it.easy) "${it.name}" else "${it.name}（需要令牌）"
        }.toTypedArray()
        // 说明放进标题：MaterialAlertDialog 同时 setMessage + setItems 时，
        // 列表会被那段文字挤掉（实测弹窗里只剩说明、选项一条不剩）。
        // 文字放标题里，列表一定会显示出来。
        MaterialAlertDialogBuilder(this)
            .setTitle("用哪个网盘（前几个填账号密码即可）")
            .setItems(labels) { _, i ->
                val d = ordered[i]
                askFields(d)
            }
            .show()
    }

    private fun askFields(d: Drive) {
        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
        }
        if (d.how.isNotBlank()) {
            root.addView(TextView(ctx).apply {
                text = d.how
                textSize = 12f
                setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
            })
        }
        // 「去哪拿」——之前只丢一个 refreshToken 字段名给用户，
        // 他既不知道这是什么，也不知道上哪弄，只能放弃。
        if (d.helpUrl.isNotBlank()) {
            root.addView(MaterialButton(ctx).apply {
                text = "去哪拿？打开说明页"
                setOnClickListener {
                    runCatching {
                        startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(d.helpUrl)
                            )
                        )
                    }
                }
            })
        }
        val eds = ArrayList<TextInputEditText>()
        for (f in d.fields) {
            root.addView(TextView(ctx).apply { text = f.label })
            root.addView(TextInputEditText(ctx).apply {
                hint = f.hint
                setSingleLine(true)
                if (f.pwd) inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }.also { eds.add(it) })
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(d.name)
            .setView(root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton("连接") { _, _ ->
                val vals = eds.map { it.text?.toString()?.trim() ?: "" }
                    .mapIndexed { i, v -> v.ifBlank { d.fields.getOrNull(i)?.def ?: "" } }
                if (vals.any { it.isBlank() }) {
                    toast("还有没填的")
                    return@setPositiveButton
                }
                val cfg = runCatching { buildConfig(d.key, vals) }
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
        "ftp" -> OpenListConfig.ftp(v[0], v[1].toIntOrNull() ?: 21, v[2], v[3])
        "sftp" -> OpenListConfig.sftp(v[0], v[1].toIntOrNull() ?: 22, v[2], v[3])
        "lanzou" -> OpenListConfig.lanzou(v[0], v[1])
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

    private fun toast(s: String) = Tips.short(this, s)
}
