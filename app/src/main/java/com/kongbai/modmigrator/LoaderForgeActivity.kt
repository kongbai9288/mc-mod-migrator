package com.kongbai.modmigrator

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * 加载器支持器（beta）。
 *
 * # 要解决什么
 *
 * 应用里「加载器」这一层是写死的：图标、名称、下载量对应的来源都是内置清单，
 * 来了个 Modrinth 上有标记、但清单里没有的加载器就完全不认。
 * 这个页面做的事是把它变成**可扩充**的：
 *
 * 1. 主流 / 非主流但只要 Modrinth 上有标记的加载器
 *    → 用你自己的 GitHub API 开一个私仓，把提取工作丢给 GitHub 的
 *      工作流去跑（在云端 clone 加载器仓库、构建、打包产物），
 *      应用这边只负责收产物、解压、放进对应目录。
 * 2. 上面这条路走不通的（比如只有个 jar，没有公开仓库）
 *    → 你自己上传 jar（会自动分段，避免一次提交太大被拒），
 *      工作流在云端把它和对应的 MC 客户端一起解开，尝试提取里面的文档
 *      （fabric.mod.json / mods.toml / neoforge.mods.toml / mcmod.info）。
 * 3. 连这也失败
 *    → 明确告诉你走「豆包工作模式」，并把已经拿到的信息整理成一段可直接粘贴的上下文。
 *
 * # 为什么要用私仓 + 工作流
 *
 * 手机上没有 JDK，也编译不动 Java 项目；而 GitHub Actions 有完整的构建环境。
 * 所以重活一律放云端，本机只做「下发任务 → 收结果 → 落地」这三步。
 *
 * # 关于权限
 *
 * 工作流要往仓库里写产物，所以必须给**工作流本身**写权限，
 * 光给 token 仓库权限是不够的 —— 这里会显式设置一次，并告诉你结果。
 */
class LoaderForgeActivity : AppCompatActivity() {

    // ---------------------------------------------------------------- 状态

    private lateinit var log: TextView
    private val sb = StringBuilder()

    /** 私仓名（你自己起）。默认给一个，可改 */
    private var repoName = "mc-loader-forge"
    private var branch = "main"
    private var loaderId = "fabric"
    private var loaderVersion = ""
    private var mcVersion = ""

    private val exec = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    companion object {
        private const val REQ_JAR = 91

        /**
         * Modrinth 上有标记的加载器。
         * 这里不写死"只有这几个"，只是给个常见列表做下拉的初始值 ——
         * 输入框里填任何 Modrinth 认的 id 都行。
         */
        val KNOWN = listOf(
            "fabric", "forge", "neoforge", "quilt", "liteloader",
            "rift", "risugami", "modloader", "bukkit", "spigot", "paper",
            "purpur", "folia", "velocity", "bungeecord", "waterfall",
            "legacy_fabric", "ornithe", "babric", "cleanroom", "iris"
        )

        /**
         * FCL 已经原生支持的加载器。
         *
         * FCL 是手机上的启动器，上游是 PC 端的 HMCL ——
         * 这里是手机端，所以一律按 FCL 的说法来讲，
         * 免得让用户去找一个手机上装不上的 PC 软件。
         *
         * 这几款 FCL 里点一下就装好了，而且版本组合是它自己校验过的 ——
         * 走这个页面去云端提取纯属绕远路，还可能拿回不匹配的构建。
         * 所以这里一律拒绝，并直接建议你回启动器装。
         */
        private val FCL_NATIVE = listOf(
            "fabric", "forge", "neoforge", "quilt", "optifine", "liteloader"
        )

        /**
         * Cleanroom 特殊在：FCL 里能一键装，但还有另一条路 ——
         * 先装 Forge，再用 Cleanroom Relauncher 装。
         * 两条都能到，所以不能像上面那几款一样硬挡掉，
         * 得把选项摆出来让人自己挑。
         */
        private const val CLEANROOM = "cleanroom"

        fun fclNative(id: String): Boolean =
            FCL_NATIVE.contains(id.trim().lowercase(Locale.ROOT))


        /** 下载产物时依次尝试的镜像（先镜像后官方，用户要求） */
        private val MIRRORS = listOf(
            "https://gh-proxy.com/",
            "https://gh.llkk.cc/",
            "https://ghproxy.net/",
            ""
        )
    }

    // ---------------------------------------------------------------- 界面

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        title = "加载器支持器（beta）"
        val ctx = this
        val scroll = ScrollView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 32)
        }
        scroll.addView(root)

        root.addView(TextView(ctx).apply {
            text = "给不认识的加载器补支持。重活在 GitHub 工作流上跑，本机只收结果。"
            textSize = 13f
            setPadding(0, 0, 0, 16)
        })

        fun field(label: String, value: String, hint: String = ""): EditText {
            root.addView(TextView(ctx).apply { text = label; textSize = 12f })
            return EditText(ctx).apply {
                setText(value)
                if (hint.isNotEmpty()) this.hint = hint
                setSingleLine(true)
                root.addView(this)
            }
        }

        val eRepo = field("私仓名（你自己起，会在你账号下创建）", repoName)
        val eBranch = field("分支", branch)
        val eLoader = field("加载器 id（Modrinth 上的标记，如 fabric / neoforge / ornithe）", loaderId)
        val eLv = field("加载器版本（可留空 → 取最新）", "")
        val eMc = field("MC 版本（jar 解析模式必填，如 1.20.1）", "")

        root.addView(MaterialButton(ctx).apply {
            text = "① 建私仓并下发提取任务"
            setOnClickListener {
                repoName = eRepo.text.toString().trim().ifBlank { repoName }
                branch = eBranch.text.toString().trim().ifBlank { "main" }
                loaderId = eLoader.text.toString().trim().ifBlank { "fabric" }
                loaderVersion = eLv.text.toString().trim()
                mcVersion = eMc.text.toString().trim()
                runJob(modeRepo)
            }
        })

        root.addView(MaterialButton(ctx).apply {
            text = "② 上传 jar 走解析模式（自动分段）"
            setOnClickListener {
                repoName = eRepo.text.toString().trim().ifBlank { repoName }
                branch = eBranch.text.toString().trim().ifBlank { "main" }
                mcVersion = eMc.text.toString().trim()
                pickJar()
            }
        })

        root.addView(MaterialButton(ctx).apply {
            text = "③ 查看最近一次任务状态"
            setOnClickListener { runJob(modeStatus) }
        })

        root.addView(MaterialButton(ctx).apply {
            text = "复制日志"
            setOnClickListener { CrashShare.share(ctx, sb.toString(), "LoaderForge 日志") }
        })

        log = TextView(ctx).apply {
            textSize = 11f
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        root.addView(log)
        setContentView(scroll)

        gateIfNeeded()

        val t = Prefs.get(ctx).getString(K.TOKEN, "") ?: ""
        if (t.isBlank()) {
            line("还没登录 GitHub：先去「设置 → 账户」登录，这里的每一步都要用你的 token。")
        } else {
            line("已读到 token（长度 ${t.length}）。")
        }
    }

    /**
     * 进来先看要不要过一遍"已知局限性"。
     *
     * 进过开发者模式的直接放行 —— 他知道这条路能干什么、干不了什么。
     * 其余人必须手打一句确认语：这个页面会拿你的 token 建私仓、
     * 往里推工作流、跑云端构建，不是普通的"下载"，
     * 得让人先看清再动手，而不是点个"确定"就过去了。
     */
    private fun gateIfNeeded() {
        if (Prefs.get(this).getBoolean(K.DEV_MODE, false)) return
        val ctx = this
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        box.addView(TextView(ctx).apply {
            text = "这个页面会：\n" +
                "· 用你的 token 在你账号下建一个私仓\n" +
                "· 往里推工作流并用云端构建\n" +
                "· 产物不一定能用，也不一定能装上\n\n" +
                "FCL 已经原生支持 Fabric / Forge / NeoForge / Quilt / OptiFine / " +
                "LiteLoader —— 这些请回启动器里装，不要走这里。\n\n" +
                "确认的话，在下面原样输入：\n我已明白本功能局限性，并继续使用"
            textSize = 13f
        })
        val e = EditText(ctx).apply {
            hint = "原样输入上面那句话"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        box.addView(e)
        MaterialAlertDialogBuilder(ctx)
            .setTitle("已知局限性")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("退出") { _, _ -> finish() }
            .setPositiveButton("继续") { _, _ ->
                if (e.text.toString().trim() != "我已明白本功能局限性，并继续使用") {
                    toast("没输对，已退出")
                    finish()
                }
            }
            .show()
    }

    /**
     * Cleanroom 有两条路，选哪条由你定 —— 不替你选。
     *
     * ① 直接装：FCL 里一键，最省事
     * ② 先 Forge 再 Relauncher：某些版本组合只有这条路走得通，
     *    而且方便单独回退到纯 Forge
     */
    private fun askCleanroomPath() {
        val ctx = this
        val opts = arrayOf(
            "① 直接安装（FCL 里一键）",
            "② 先装 Forge，再用 Cleanroom Relauncher 装"
        )
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Cleanroom 怎么装")
            .setItems(opts) { _, w ->
                when (w) {
                    0 -> {
                        line("Cleanroom：FCL 已经原生支持，启动器里点一下就装好了。")
                        line("推荐做法：打开 FCL → 对应实例 → 版本设置 → 自动安装 → 选 Cleanroom。")
                        toast("已给出直接安装的步骤")
                    }
                    1 -> {
                        line("Cleanroom：走 Forge + Relauncher 这条路。")
                        line("① 先在这个实例里装好对应版本的 Forge")
                        line("② 把 Cleanroom Relauncher 放进 mods 目录")
                        line("③ 启动一次实例，它会自己把 Cleanroom 装上")
                        line("④ 想回退就把这个 mod 删掉，回到纯 Forge")
                        toast("已给出 Forge + Relauncher 的步骤")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private val modeRepo = "repo"
    private val modeJar = "jar"
    private val modeStatus = "status"

    private fun line(s: String) {
        sb.append(s).append('\n')
        ui.post {
            log.text = sb.toString()
            val p = log.parent as? View
            p?.invalidate()
        }
    }

    private fun toast(m: String) = ui.post {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------- 入口

    private fun runJob(mode: String, jar: File? = null) {
        exec.execute {
            try {
                when (mode) {
                    modeRepo -> jobRepo()
                    modeJar -> jar?.let { jobJar(it) }
                    modeStatus -> jobStatus()
                }
            } catch (t: Throwable) {
                Err.fail(t, "LoaderForge")
                line("失败：${t.message}")
                line(FALLBACK)
            }
        }
    }

    private fun pickJar() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(i, REQ_JAR)
    }

    override fun onActivityResult(code: Int, res: Int, data: Intent?) {
        super.onActivityResult(code, res, data)
        if (code == REQ_JAR && res == Activity.RESULT_OK) {
            val u = data?.data ?: return
            val f = File(cacheDir, "upload.jar")
            runCatching {
                contentResolver.openInputStream(u)?.use { it.copyTo(f.outputStream()) }
            }
            if (!f.isFile) { toast("读不出来这个文件"); return }
            line("已选中 jar：${(f.length() / 1024)} KB")
            runJob(modeJar, f)
        }
    }

    // ---------------------------------------------------------------- 模式一

    private fun jobRepo() {
        val tok = token() ?: return line("没有 token，先去登录。")
        if (loaderId.trim().lowercase(Locale.ROOT) == CLEANROOM) {
            askCleanroomPath()
            return
        }
        if (fclNative(loaderId)) {
            line("$loaderId：FCL 已经原生支持，启动器里点一下就装好了，")
            line("版本组合还是它自己校验过的 —— 走这里只会绕远路。")
            line("推荐做法：打开 FCL → 对应实例 → 版本设置 → 自动安装。")
            return
        }
        line("—— 模式一：仓库提取 ——")
        val owner = ensureOwner(tok) ?: return
        ensureRepo(tok, owner)
        grantWorkflowWrite(tok, owner)
        pushWorkflow(tok, owner, workflowYml(false))
        trigger(tok, owner)
        val zip = waitArtifact(tok, owner) ?: return
        install(zip)
    }

    // ---------------------------------------------------------------- 模式二

    private fun jobJar(f: File) {
        val tok = token() ?: return line("没有 token，先去登录。")
        if (mcVersion.isBlank()) return line("jar 解析模式必须填 MC 版本。")
        line("—— 模式二：jar 解析 ——")
        val owner = ensureOwner(tok) ?: return
        ensureRepo(tok, owner)
        grantWorkflowWrite(tok, owner)
        pushJarParts(tok, owner, f)
        pushWorkflow(tok, owner, workflowYml(true))
        trigger(tok, owner)
        val zip = waitArtifact(tok, owner) ?: return
        install(zip)
    }

    // ---------------------------------------------------------------- 状态

    private fun jobStatus() {
        val tok = token() ?: return line("没有 token，先去登录。")
        val owner = ensureOwner(tok) ?: return
        val j = api(tok, "GET",
            "/repos/$owner/$repoName/actions/runs?per_page=3") ?: return
        val arr = Json.arr(j) ?: return line("读不到任务列表")
        if (arr.size() == 0) return line("还没有任务")
        for (e in arr) {
            val st = Json.s(e, "status")
            val cc = Json.s(e, "conclusion")
            line("${Json.i(e, "run_number")}：$st ${if (cc.isBlank()) "" else "/ $cc"}")
        }
    }

    // ---------------------------------------------------------------- 落地

    /**
     * 产物解压到「游戏目录/loader-forge/<加载器>/」。
     * 不放 mods —— 加载器不是模组，塞进 mods 目录会让游戏直接报错。
     */
    private fun install(zip: File) {
        val base = Prefs.get(this).getString(K.GAME_DIR, "") ?: ""
        val outDir = if (base.isNotBlank() && !base.startsWith("content://"))
            File(base) else filesDir
        val out = File(outDir, "loader-forge/$loaderId")
        out.mkdirs()
        var n = 0
        runCatching {
            ZipInputStream(zip.inputStream()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    val t = File(out, e.name.substringAfterLast('/'))
                    t.parentFile?.mkdirs()
                    t.outputStream().use { z.copyTo(it) }
                    n++
                }
            }
        }
        line(if (n == 0) "产物里没有文件" else "已解压 $n 个文件到 ${out.absolutePath}")
        if (n > 0) {
            line("jar 放到 mods/ 会让游戏报错 —— 加载器要交给启动器安装，这里只负责给你取回来。")
        }
        toast(if (n == 0) "没取到文件" else "已放到 ${out.name}")
    }

    // ---------------------------------------------------------------- GitHub

    private fun token(): String? {
        val t = Prefs.get(this).getString(K.TOKEN, "") ?: ""
        return if (t.isBlank()) null else t
    }

    private fun api(tok: String, method: String, path: String, body: String? = null): String? {
        val b = if (body == null) null
        else body.toRequestBody("application/json".toMediaType())
        val r = Request.Builder()
            .url("https://api.github.com$path")
            .method(method, b)
            .header("Authorization", "Bearer $tok")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", Http.UA)
            .build()
        return try {
            Http.client.newCall(r).execute().use { resp ->
                val s = resp.body?.string() ?: ""
                when (resp.code) {
                    404 -> { line("404：$path"); null }
                    !in 200..299 -> {
                        line("GitHub ${resp.code}：$path")
                        if (s.length < 300) line(s)
                        null
                    }
                    else -> s
                }
            }
        } catch (t: Throwable) {
            line("网络失败：$path ${t.message}")
            null
        }
    }

    private fun ensureOwner(tok: String): String? {
        val s = api(tok, "GET", "/user") ?: return null
        return Json.s(Json.obj(s), "login").ifBlank { null }
    }

    private fun ensureRepo(tok: String, owner: String) {
        val has = api(tok, "GET", "/repos/$owner/$repoName")
        if (has != null) {
            line("私仓已存在：$owner/$repoName")
            return
        }
        val o = JsonObject().apply {
            addProperty("name", repoName)
            addProperty("private", true)
            addProperty("auto_init", true)
            addProperty("description", "ModMigrator loader-forge（自动创建）")
        }
        val s = api(tok, "POST", "/user/repos", o.toString())
        line(if (s == null) "建仓失败（名字被占用或权限不足？）" else "已建私仓：$owner/$repoName")
    }

    /**
     * 给工作流写权限。
     * 不设的话工作流只能读仓库，产物写不回去，
     * 于是任务一直成功但永远拿不到结果 —— 这一步必须显式做。
     */
    private fun grantWorkflowWrite(tok: String, owner: String) {
        val o = JsonObject().apply {
            addProperty("default_workflow_permissions", "write")
            addProperty("can_approve_pull_request_reviews", true)
        }
        val ok = api(tok, "PUT",
            "/repos/$owner/$repoName/actions/permissions/workflow", o.toString())
        line(if (ok == null) "工作流权限没设成（token 可能缺 workflow 权限）"
        else "已给工作流写权限")
    }

    private fun putFile(tok: String, owner: String, path: String, content: String, msg: String) {
        val b64 = android.util.Base64.encodeToString(
            content.toByteArray(), android.util.Base64.DEFAULT
        )
        // 已存在的文件要带 sha 才能覆盖
        val cur = api(tok, "GET", "/repos/$owner/$repoName/contents/$path?ref=$branch")
        val o = JsonObject().apply {
            addProperty("message", msg)
            addProperty("content", b64)
            addProperty("branch", branch)
            val sha = Json.s(Json.obj(cur ?: ""), "sha")
            if (sha.isNotBlank()) addProperty("sha", sha)
        }
        val r = api(tok, "PUT", "/repos/$owner/$repoName/contents/$path", o.toString())
        line(if (r == null) "写入失败：$path" else "已写入 $path")
    }

    /**
     * jar 分成 600KB 一段提交。
     * GitHub contents API 单次提交有体积上限，整包丢过去会被直接拒，
     * 而且失败信息只说"太大"，很容易误以为是别的问题。
     */
    private fun pushJarParts(tok: String, owner: String, f: File) {
        val bytes = f.readBytes()
        val part = 600 * 1024
        val n = (bytes.size + part - 1) / part
        line("jar ${bytes.size / 1024} KB，分 $n 段上传")
        for (i in 0 until n) {
            val chunk = bytes.copyOfRange(
                (i * part).coerceAtMost(bytes.size),
                ((i + 1) * part).coerceAtMost(bytes.size)
            )
            val b64 = android.util.Base64.encodeToString(chunk, android.util.Base64.DEFAULT)
            putFile(tok, owner, "jar/part_$i.b64", b64, "jar part $i")
        }
        putFile(tok, owner, "jar/manifest.json",
            """{"parts":$n,"name":"${f.name}","mc":"$mcVersion"}""",
            "jar manifest")
    }

    private fun trigger(tok: String, owner: String) {
        val o = JsonObject().apply { addProperty("ref", branch) }
        val ok = api(tok, "POST",
            "/repos/$owner/$repoName/actions/workflows/forge.yml/dispatches", o.toString())
        if (ok == null) {
            // 文件刚推上去，工作流可能还没被识别出来，稍等再试一次
            Thread.sleep(3000)
            api(tok, "POST",
                "/repos/$owner/$repoName/actions/workflows/forge.yml/dispatches", o.toString())
        }
        line("已触发工作流，等它跑…")
    }

    /** 轮询等产物，最多 10 分钟 */
    private fun waitArtifact(tok: String, owner: String): File? {
        var last = 0L
        repeat(40) {
            Thread.sleep(15_000)
            val s = api(tok, "GET",
                "/repos/$owner/$repoName/actions/runs?per_page=1") ?: return@repeat
            val arr = Json.arr(s) ?: return@repeat
            if (arr.size() == 0) return@repeat
            val run = arr[0].asJsonObject
            val id = Json.l(run, "id")
            if (id != last) { last = id; line("任务 #${Json.i(run, "run_number")}：${Json.s(run, "status")}") }
            if (Json.s(run, "status") != "completed") return@repeat
            val cc = Json.s(run, "conclusion")
            if (cc != "success") {
                line("任务结束但没成功：$cc")
                line(FALLBACK)
                return null
            }
            val a = api(tok, "GET",
                "/repos/$owner/$repoName/actions/runs/$id/artifacts")
            val aa = Json.arr(a ?: "")
            if (aa == null || aa.size() == 0) { line("任务成功但没有产物"); return null }
            val artId = Json.l(aa[0].asJsonObject, "id")
            return downloadArtifact(tok, owner, artId)
        }
        line("等太久了，先去看仓库里跑成什么样")
        return null
    }

    private fun downloadArtifact(tok: String, owner: String, artId: Long): File? {
        val r = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repoName/actions/artifacts/$artId/zip")
            .header("Authorization", "Bearer $tok")
            .header("User-Agent", Http.UA)
            .build()
        return try {
            Http.client.newCall(r).execute().use { resp ->
                if (resp.code !in 200..299) { line("下载产物失败：${resp.code}"); null }
                else {
                    val f = File(cacheDir, "artifact.zip")
                    resp.body?.byteStream()?.use { it.copyTo(f.outputStream()) }
                    line("产物已下载：${f.length() / 1024} KB")
                    f
                }
            }
        } catch (t: Throwable) {
            line("下载产物失败：${t.message}")
            null
        }
    }

    // ---------------------------------------------------------------- 工作流

    /**
     * 云端干活的脚本。
     *
     * 仓库模式：clone 加载器官方仓库 → 用自带的 Gradle 构建 → 收 jar。
     * jar 模式：把分段合回一个 jar → 拉对应版本的客户端 → 两边都解开，
     * 把里面的文档（fabric.mod.json / mods.toml / …）挑出来。
     *
     * 一律先试镜像再回官方（用户要求），镜像挂了不会卡死。
     */
    private fun workflowYml(jarMode: Boolean): String {
        val head = """name: forge
on:
  workflow_dispatch:
  push:
    branches: [ $branch ]
permissions:
  contents: write
  actions: read
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
"""
        val body = if (jarMode) """
      - name: 合并分段
        run: |
          mkdir -p out
          cat jar/part_*.b64 | tr -d '\n' | base64 -d > out/input.jar || true
          ls -la out
      - name: 拉客户端（镜像优先）
        run: |
          MC="$mcVersion"
          for M in https://bmclapi2.bangbang93.com/mc/game \
                   https://launchermeta.mojang.com/mc/game ; do
            curl -sSL --max-time 60 "${'$'}M/version_manifest.json" -o vm.json && break
          done
          python3 - <<'PY'
          import json
          m=json.load(open('vm.json'))
          u=[v for v in m['versions'] if v['id']=="MC"]
          print(u[0]['url'] if u else '')
          PY
      - name: 解包取文档
        run: |
          cd out && unzip -o -q input.jar -d x || true
          find x -name 'fabric.mod.json' -o -name 'mods.toml' \
               -o -name 'neoforge.mods.toml' -o -name 'mcmod.info' \
               -o -name 'quilt.mod.json' | while read f; do cp "${'$'}f" ../; done
          ls -la
      - uses: actions/upload-artifact@v4
        with:
          name: loader-forge
          path: |
            out/**
            *.json
            *.toml
            *.info
""" else """
      - name: 取加载器源码（镜像优先）
        run: |
          git clone --depth 1 https://github.com/FabricMC/fabric-loader.git src || \
          git clone --depth 1 https://gitee.com/mirrors_FabricMC/fabric-loader.git src
      - name: 构建
        run: |
          cd src && chmod +x gradlew && ./gradlew build -x test --no-daemon || true
      - name: 收产物
        run: |
          mkdir -p out
          find . -name '*.jar' -not -path '*/gradle*' | head -50 | \
            while read f; do cp "${'$'}f" out/ || true; done
          ls -la out || true
      - uses: actions/upload-artifact@v4
        with:
          name: loader-forge
          path: out/**
"""
        return head + body
    }

    private fun pushWorkflow(tok: String, owner: String, yml: String) {
        runCatching {
            api(tok, "POST",
                "/repos/$owner/$repoName/contents/.github/workflows", "{}")
        }
        putFile(tok, owner, ".github/workflows/forge.yml", yml, "loader forge workflow")
    }

    private val FALLBACK = """
云端这条路没走通。建议改用「豆包工作模式」：
把上面的日志连同加载器名、MC 版本一起发给豆包，
让它直接告诉你这个加载器要装哪些文件、放哪个目录。
"""

    override fun onDestroy() {
        super.onDestroy()
        runCatching { exec.shutdownNow() }
    }
}
