package com.opentransfer.openlist

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPSClient
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import android.util.Xml
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.File
import java.io.StringReader
import java.util.concurrent.TimeUnit

/**
 * 直连实现（WebDAV / FTP / FTPS / SFTP）。
 *
 * # 为什么不再走 gomobile 的 bridge
 *
 * 上一版接的是一个 Go 编译出来的 AAR，App 只会拿到「成功/失败」两个字，
 * 中间发生什么完全看不见 —— 连不上就只能干瞪眼，
 * 连是连上了还是参数不对、是网络问题还是凭据问题都分不出来。
 * 而且它是个黑盒二进制，出问题无法在本工程内定位。
 *
 * 这四类都是**标准协议**，用现成的成熟库直连即可：
 *   · WebDAV → OkHttp（PROPFIND / PUT / GET / MKCOL / DELETE / MOVE）
 *   · FTP / FTPS → Apache Commons Net
 *   · SFTP → sshj
 * 走的都是本工程本来就有的依赖，没有新增二进制。
 *
 * 其余网盘（阿里云盘 / 百度 / 夸克…）各自是私有 API，
 * 没有官方公开给第三方 App 的稳定接口，硬猜接口只会一直连不上，
 * 所以这里明确报「暂不支持直连」，不假装能用。
 */
internal class OpenListDrive(
    private val provider: String,
    private val configJson: String,
) : CloudDrive {

    private val cfg: JSONObject = try { JSONObject(configJson) } catch (_: Throwable) { JSONObject() }

    /** 各家配置里同一个含义的字段名字不一样，这里按常见别名依次取。 */
    private fun first(vararg keys: String): String {
        for (k in keys) {
            val v = cfg.optString(k, "").trim()
            if (v.isNotBlank()) return v
        }
        for (k in keys) {
            val v = cfg.opt(k)
            if (v != null && v is Number) return v.toString()
        }
        return ""
    }

    private fun kind(): String {
        val p = provider.lowercase()
        return when {
            p.contains("webdav") -> "webdav"
            p.contains("sftp") -> "sftp"
            p.contains("ftp") -> "ftp"
            p.startsWith("aliyun") || p.contains("alicloud") -> "aliyun"
            p.contains("baidu") -> "baidu"
            else -> "unsupported"
        }
    }

    private fun host(): String = first("url", "host", "address", "server", "endpoint", "api_url_address")
        .trimEnd('/')
    private fun user(): String = first("username", "user", "account", "access_key_id", "access_key")
    private fun pwd(): String = first("password", "pass", "secret", "access_token", "refresh_token",
        "access_key_secret", "secret_key", "token")
    private fun root(): String = first("root_folder_id", "root", "base_path", "remote_path")
        .let { if (it.isBlank() || it == "root") "/" else it.trimEnd('/') }
    private fun port(): Int = first("port").toIntOrNull() ?: when (kind()) {
        "ftp" -> 21; "sftp" -> 22; else -> 0
    }

    private fun unsupported(): Nothing = throw OpenListException(
        when (kind()) {
            "aliyun" -> "阿里云盘没有开放给第三方 App 的稳定直连接口（官方只给了网页/自建服务端），暂不支持直连。建议改用 WebDAV 或 FTP/SFTP。"
            "baidu" -> "百度网盘第三方 App 需要官方授权且接口随时调整，暂不支持直连。建议改用 WebDAV 或 FTP/SFTP。"
            else -> "「$provider」暂不支持直连，目前可用：WebDAV、FTP、FTPS、SFTP。"
        }
    )

    // ------------------------------------------------------------ 统一入口

    /** 把底层异常翻成人能看懂的话，别再抛一串英文栈。 */
    private fun friendly(e: Throwable): String {
        val m = e.message ?: e.javaClass.simpleName
        val low = m.lowercase()
        return when {
            low.contains("econnrefused") || low.contains("connection refused") -> "连不上：对方拒绝了连接（地址或端口不对？）"
            low.contains("timeout") || low.contains("timed out") -> "连不上：超时（地址不通、被墙或端口没开）"
            low.contains("unknownhost") || low.contains("unknown host") -> "连不上：域名解析不了，检查一下地址"
            low.contains("530") || low.contains("auth") || low.contains("login") ||
                low.contains("password") || low.contains("530 login") -> "账号或密码不对（FTP 的 530 就是这个）"
            low.contains("401") || low.contains("403") -> "没有权限：账号密码不对，或该目录不允许访问"
            low.contains("404") -> "路径不存在"
            low.contains("550") -> "服务器拒绝了操作（550）：通常是没权限或路径不存在"
            low.contains("network is unreachable") -> "网络不可达"
            else -> m
        }
    }

    private inline fun <T> runIo(block: () -> T): OpenListResult<T> =
        try {
            OpenListResult.success(block())
        } catch (e: Exception) {
            OpenListResult.failure(friendly(e))
        }

    private fun abs(path: String): String {
        val p = if (path.startsWith("/")) path else "/$path"
        return if (root() == "/") p else root() + p
    }

    // ------------------------------------------------------------ 接口实现

    override suspend fun testConnection(): OpenListResult<Unit> = withContext(Dispatchers.IO) {
        runIo {
            when (kind()) {
                "webdav" -> { webdav().list(abs("/")); Unit }
                "ftp" -> { val f = ftp(); try { f.ensure() } finally { f.close() }; Unit }
                "sftp" -> { val s2 = sftp(); try { s2.ls(abs("/")) } finally { s2.close() }; Unit }
                else -> unsupported()
            }
        }
    }

    override suspend fun list(path: String): OpenListResult<List<RemoteFile>> =
        withContext(Dispatchers.IO) {
            runIo {
                when (kind()) {
                    "webdav" -> webdav().use { it.list(abs(path)) }
                    "ftp" -> ftp().use { it.list(abs(path)) }
                    "sftp" -> sftp().use { it.ls(abs(path)) }
                    else -> unsupported()
                }
            }
        }

    override suspend fun stat(path: String): OpenListResult<RemoteFile> = withContext(Dispatchers.IO) {
        runIo {
            val parent = abs(path).substringBeforeLast('/').ifBlank { "/" }
            val name = abs(path).substringAfterLast('/')
            val all = when (kind()) {
                "webdav" -> webdav().use { it.list(parent) }
                "ftp" -> ftp().use { it.list(parent) }
                "sftp" -> sftp().use { it.ls(parent) }
                else -> unsupported()
            }
            all.firstOrNull { it.name == name }
                ?: throw OpenListException("找不到 $path")
        }
    }

    override suspend fun upload(
        local: File,
        remotePath: String,
        onProgress: ((Long) -> Unit)?,
    ): OpenListResult<RemoteFile> = withContext(Dispatchers.IO) {
        runIo {
            val target = abs(remotePath)
            val dir = target.substringBeforeLast('/').ifBlank { "/" }
            val name = target.substringAfterLast('/')
            onProgress?.invoke(0)
            when (kind()) {
                "webdav" -> webdav().use { it.put(target, local) }
                "ftp" -> ftp().use { it.upload(dir, name, local) }
                "sftp" -> sftp().use { it.up(dir, name, local) }
                else -> unsupported()
            }
            onProgress?.invoke(local.length())
            RemoteFile(
                path = target, name = name, size = local.length(),
                modifiedAt = System.currentTimeMillis(), isDirectory = false
            )
        }
    }

    override suspend fun download(
        remotePath: String,
        local: File,
        onProgress: ((Long) -> Unit)?,
    ): OpenListResult<Unit> = withContext(Dispatchers.IO) {
        runIo {
            val src = abs(remotePath)
            onProgress?.invoke(0)
            when (kind()) {
                "webdav" -> webdav().use { it.get(src, local) }
                "ftp" -> ftp().use { it.download(src, local) }
                "sftp" -> sftp().use { it.down(src, local) }
                else -> unsupported()
            }
            onProgress?.invoke(local.length())
        }
    }

    override suspend fun delete(path: String): OpenListResult<Unit> = withContext(Dispatchers.IO) {
        runIo {
            when (kind()) {
                "webdav" -> webdav().use { it.del(abs(path)) }
                "ftp" -> ftp().use { it.rm(abs(path)) }
                "sftp" -> sftp().use { it.rm(abs(path)) }
                else -> unsupported()
            }
        }
    }

    override suspend fun mkdir(path: String): OpenListResult<Unit> = withContext(Dispatchers.IO) {
        runIo {
            when (kind()) {
                "webdav" -> webdav().use { it.mkcol(abs(path)) }
                "ftp" -> ftp().use { it.mkdir(abs(path)) }
                "sftp" -> sftp().use { it.mkdir(abs(path)) }
                else -> unsupported()
            }
        }
    }

    override suspend fun ensureDirectory(path: String): OpenListResult<Unit> =
        withContext(Dispatchers.IO) {
            runIo {
                var cur = "/"
                for (part in abs(path).trim('/').split('/').filter { it.isNotBlank() }) {
                    val child = if (cur == "/") "/$part" else "$cur/$part"
                    val exists = try {
                        when (kind()) {
                            "webdav" -> webdav().use { it.list(cur) }.any { it.name == part }
                            "ftp" -> ftp().use { it.list(cur) }.any { it.name == part }
                            "sftp" -> sftp().use { it.ls(cur) }.any { it.name == part }
                            else -> true
                        }
                    } catch (_: Throwable) { false }
                    if (!exists) {
                        when (kind()) {
                            "webdav" -> webdav().use { it.mkcol(child) }
                            "ftp" -> ftp().use { it.mkdir(child) }
                            "sftp" -> sftp().use { it.mkdir(child) }
                        }
                    }
                    cur = child
                }
            }
        }

    override suspend fun exists(path: String): OpenListResult<Boolean> = withContext(Dispatchers.IO) {
        runIo {
            val parent = abs(path).substringBeforeLast('/').ifBlank { "/" }
            val name = abs(path).substringAfterLast('/')
            val all = when (kind()) {
                "webdav" -> webdav().use { it.list(parent) }
                "ftp" -> ftp().use { it.list(parent) }
                "sftp" -> sftp().use { it.ls(parent) }
                else -> unsupported()
            }
            all.any { it.name == name }
        }
    }

    override suspend fun rename(oldPath: String, newPath: String): OpenListResult<Unit> =
        withContext(Dispatchers.IO) {
            runIo {
                when (kind()) {
                    "webdav" -> webdav().use { it.move(abs(oldPath), abs(newPath)) }
                    "ftp" -> ftp().use { it.rename(abs(oldPath), abs(newPath)) }
                    "sftp" -> sftp().use { it.mv(abs(oldPath), abs(newPath)) }
                    else -> unsupported()
                }
            }
        }

    override suspend fun copy(source: String, destination: String): OpenListResult<Unit> =
        withContext(Dispatchers.IO) {
            OpenListResult.failure("这个协议不支持直接复制远端文件，请先下载再上传")
        }

    override suspend fun getDownloadUrl(path: String): OpenListResult<String> =
        withContext(Dispatchers.IO) {
            when (kind()) {
                "webdav" -> OpenListResult.success(host() + abs(path))
                else -> OpenListResult.failure(
                    "只有 WebDAV 能直接给出下载地址；FTP/SFTP 请直接下载")
            }
        }

    override suspend fun getDirectorySize(path: String): OpenListResult<Long> =
        withContext(Dispatchers.IO) {
            runIo {
                val all = when (kind()) {
                    "webdav" -> webdav().use { it.list(abs(path)) }
                    "ftp" -> ftp().use { it.list(abs(path)) }
                    "sftp" -> sftp().use { it.ls(abs(path)) }
                    else -> unsupported()
                }
                all.sumOf { it.size }
            }
        }

    override suspend fun move(source: String, destination: String): OpenListResult<Unit> =
        rename(source, destination)

    fun destroy() {}

    // ------------------------------------------------------------ 后端

    private fun ok(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private inner class Dav : java.io.Closeable {
        private val c = ok()
        private val base = host()
        private val auth = Credentials.basic(user(), pwd())

        private fun req(url: String, method: String, body: okhttp3.RequestBody? = null): okhttp3.Response {
            val b = Request.Builder().url(url).header("Authorization", auth)
            if (body != null) b.method(method, body) else b.method(method, null)
            val r = c.newCall(b.build()).execute()
            if (r.code >= 400) throw OpenListException("$method 失败：HTTP ${r.code}")
            return r
        }

        fun list(path: String): List<RemoteFile> {
            val url = base + path.replace(" ", "%20")
            val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop>
                <d:displayname/><d:getcontentlength/><d:getlastmodified/><d:resourcetype/>
                </d:prop></d:propfind>""".trimIndent()
                .toRequestBody("application/xml".toMediaType())
            val r = req(url, "PROPFIND", body)
            val xml = r.body?.string() ?: ""
            return parsePropfind(xml)
        }

        fun put(path: String, local: File) {
            val body = local.readBytes().toRequestBody("application/octet-stream".toMediaType())
            req(base + path.replace(" ", "%20"), "PUT", body).close()
        }

        fun get(path: String, local: File) {
            val r = req(base + path.replace(" ", "%20"), "GET")
            r.body?.byteStream()?.use { input ->
                local.outputStream().use { out -> input.copyTo(out) }
            } ?: throw OpenListException("下载失败：没有内容")
        }

        fun del(path: String) { req(base + path.replace(" ", "%20"), "DELETE").close() }
        fun mkcol(path: String) { runCatching { req(base + path.replace(" ", "%20"), "MKCOL").close() } }
        override fun close() {}

        fun move(from: String, to: String) {
            val b = Request.Builder()
                .url(base + from.replace(" ", "%20"))
                .header("Authorization", auth)
                .header("Destination", base + to.replace(" ", "%20"))
                .header("Overwrite", "T")
                .method("MOVE", null)
                .build()
            val r = c.newCall(b).execute()
            if (r.code >= 400) throw OpenListException("移动失败：HTTP ${r.code}")
        }
    }

    private fun webdav() = Dav()

    /** PROPFIND 的 207 响应。不同服务器命名空间前缀不同，所以只认 localName。 */
    private fun parsePropfind(xml: String): List<RemoteFile> {
        val out = ArrayList<RemoteFile>()
        val p: XmlPullParser = Xml.newPullParser()
        p.setInput(StringReader(xml))
        var href = ""
        var len = 0L
        var mod = 0L
        var isDir = false
        var depth = 0
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (p.name?.substringAfter(':')) {
                    "response" -> { depth = 1; href = ""; len = 0; mod = 0; isDir = false }
                    "href" -> if (depth == 1) href = p.nextText()
                    "getcontentlength" -> if (depth == 1) len = p.nextText().toLongOrNull() ?: 0
                    "getlastmodified" -> if (depth == 1) mod = runCatching {
                        java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US)
                            .parse(p.nextText())?.time ?: 0
                    }.getOrDefault(0)
                    "collection" -> if (depth == 1) isDir = true
                }
                XmlPullParser.END_TAG -> if (p.name?.substringAfter(':') == "response" && depth == 1) {
                    depth = 0
                    val raw = href.trimEnd('/')
                    val name = raw.substringAfterLast('/')
                    if (name.isNotBlank()) {
                        out.add(RemoteFile(
                            path = raw, name = name, size = len,
                            modifiedAt = mod, isDirectory = isDir))
                    }
                }
            }
            event = p.next()
        }
        return out
    }

    private inner class Ftp : java.io.Closeable {
        private val c: FTPClient = if (host().startsWith("ftps", true) ||
            first("ssl", "tls", "implicit_tls").equals("true", true)) FTPSClient() else FTPClient()

        init {
            c.connectTimeout = 20000
            c.connect(host().substringAfter("://").substringBefore('/'), port())
            c.enterLocalPassiveMode()
            c.setFileType(FTP.BINARY_FILE_TYPE)
        }

        private var logged = false

        fun ensure() {
            if (!c.isConnected) {
                c.connect(host().substringAfter("://").substringBefore('/'), port())
                c.enterLocalPassiveMode()
                c.setFileType(FTP.BINARY_FILE_TYPE)
            }
            if (!logged) {
                if (!c.login(user(), pwd())) throw OpenListException("登录失败：账号或密码不对")
                logged = true
            }
        }

        fun list(path: String): List<RemoteFile> {
            ensure()
            val arr = c.listFiles(path) ?: return emptyList()
            return arr.filter { it.name != "." && it.name != ".." }.map {
                RemoteFile(
                    path = path.trimEnd('/') + "/" + it.name, name = it.name,
                    size = it.size, modifiedAt = it.timestamp?.timeInMillis ?: 0,
                    isDirectory = it.isDirectory)
            }
        }

        fun upload(dir: String, name: String, local: File) {
            ensure()
            runCatching { c.makeDirectory(dir) }
            c.changeWorkingDirectory(dir)
            local.inputStream().use { ins ->
                if (!c.storeFile(name, ins)) throw OpenListException("上传失败：${c.replyString}")
            }
        }

        fun download(path: String, local: File) {
            ensure()
            local.outputStream().use { out ->
                if (!c.retrieveFile(path, out)) throw OpenListException("下载失败：${c.replyString}")
            }
        }

        fun rm(path: String) {
            ensure()
            if (!c.deleteFile(path)) {
                runCatching { c.removeDirectory(path) }
            }
        }

        fun mkdir(path: String) { ensure(); c.makeDirectory(path) }

        fun rename(from: String, to: String) {
            ensure()
            if (!c.rename(from, to)) throw OpenListException("改名失败：${c.replyString}")
        }

        override fun close() { runCatching { c.logout() }; runCatching { c.disconnect() } }
    }

    private fun ftp() = Ftp()

    private inner class Sftp : java.io.Closeable {
        private val ssh = SSHClient()

        init {
            ssh.addHostKeyVerifier(PromiscuousVerifier())
            ssh.connect(host().substringAfter("://").substringBefore('/'), port())
            val pw = pwd()
            if (user().isBlank()) throw OpenListException("没填用户名")
            ssh.authPassword(user(), pw)
        }

        private fun s(): SFTPClient = ssh.newSFTPClient()

        fun ls(path: String): List<RemoteFile> {
            s().use { c ->
                return c.ls(path).filter { it.name != "." && it.name != ".." }.map {
                    RemoteFile(
                        path = path.trimEnd('/') + "/" + it.name, name = it.name,
                        size = it.attributes.size, modifiedAt = it.attributes.mtime * 1000L,
                        isDirectory = it.isDirectory)
                }
            }
        }

        fun up(dir: String, name: String, local: File) {
            s().use { c ->
                runCatching { c.mkdir(dir) }
                c.put(local.absolutePath, "$dir/$name")
            }
        }

        fun down(path: String, local: File) { s().use { c -> c.get(path, local.absolutePath) } }
        fun rm(path: String) { s().use { c -> runCatching { c.rm(path) }; runCatching { c.rmdir(path) } } }
        fun mkdir(path: String) { s().use { c -> c.mkdir(path) } }
        fun mv(from: String, to: String) { s().use { c -> c.rename(from, to) } }
        override fun close() { runCatching { ssh.disconnect() } }
    }

    private fun sftp() = Sftp()
}
