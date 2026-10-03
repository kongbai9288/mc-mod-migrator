package com.kongbai.modmigrator

import java.io.InputStream
import java.util.Locale

/**
 * 远程文件访问：**FTP / FTPS / SFTP**。
 *
 * 为什么要有它，而不是继续在「账号密码登录面板」上打补丁：
 *
 * Pterodactyl 的客户端接口**只接受 `ptlc_` 开头的 Client API Key**，
 * 官方文档明确写了 Key 只能由用户在 /account/api 手动创建，
 * **不存在任何"用账号密码换 Key"的接口**。
 * 所以"填面板账号密码登录"这条路从设计上就走不通。
 *
 * 但**绝大多数租来的服务器都开放 FTP 或 SSH(SFTP)**，
 * 而且用的就是账号密码。用它直接读 mods / plugins 目录，
 * 能覆盖"我只是想看看服务器上装了哪些模组、有没有更新"这个真实需求。
 *
 * 用的库都是宽松许可（Apache-2.0 / BSD，不传染）：
 *   · commons-net  → FTP / FTPS
 *   · sshj         → SFTP
 *   · conscrypt    → sshj 在 Android 上的算法提供者（见 build.gradle）
 *
 * ⚠️ 这一版重写的三处要点：
 *
 *  1. **FTPS 必须走 FTPSClient**。
 *     之前对 FTPS 用的是 `FTPClient.execAuthTLS()` —— 那只加密了**控制通道**，
 *     数据通道（列目录、下载）仍然是明文。等于挂了把锁但门没关。
 *     现在显式 FTPS 用 FTPSClient，并 `execPROT("P")` 把数据通道也加密。
 *
 *  2. **SFTP 在 Android 上必须有 Conscrypt**（已在 App 里装）。
 *     否则握手阶段就抛 "The BC provider no longer provides
 *     an implementation for MessageDigest.SHA-256"，连都连不上。
 *
 *  3. **空指针**。`listFiles()` / `ls()` 在目录不存在时可能返回 null 或抛异常，
 *     之前直接 for 循环会崩，现在一律兜住并返回空列表。
 */
object RemoteFs {

    enum class Kind(val label: String) {
        FTP("FTP（明文）"),
        FTPS("FTPS（TLS 加密）"),
        SFTP("SFTP（SSH）")
    }

    data class Conf(
        var kind: Kind = Kind.FTP,
        var host: String = "",
        var port: Int = 0,          // 0 = 用协议默认值
        var user: String = "",
        var pass: String = "",
        /** 起始目录，留空表示用户根目录 */
        var root: String = ""
    )

    data class Entry(
        var name: String = "",
        var path: String = "",
        var isDir: Boolean = false,
        var size: Long = 0
    )

    /** 默认端口 */
    fun defaultPort(k: Kind): Int = when (k) {
        Kind.FTP -> 21
        Kind.FTPS -> 21
        Kind.SFTP -> 22
    }

    private const val TIMEOUT_MS = 15000

    /**
     * 建好并登录的 FTP 连接。
     * 用完必须 close（里面会 logout + disconnect）。
     */
    private class FtpConn(val raw: org.apache.commons.net.ftp.FTPClient) {
        fun close() {
            runCatching { if (raw.isConnected) raw.logout() }
            runCatching { raw.disconnect() }
        }
    }

    /**
     * 连接 + 登录。
     * @return 失败时返回一句人话原因（null 表示成功）
     *
     * 被动模式是国内网络的**必须项**：
     * 主动模式要服务器反过来连手机的端口，
     * 而手机几乎一定在 NAT 后面，必失败。
     */
    private fun connectFtp(c: Conf, port: Int): Pair<FtpConn?, String?> {
        // FTPS（显式 TLS）必须用 FTPSClient：
        // FTPClient 只能做 AUTH TLS，数据通道还是明文
        val f: org.apache.commons.net.ftp.FTPClient =
            if (c.kind == Kind.FTPS) {
                org.apache.commons.net.ftp.FTPSClient(true).apply {
                    // 数据通道也要加密，否则只锁了控制通道没意义
                    execPROT("P")
                }
            } else {
                org.apache.commons.net.ftp.FTPClient()
            }
        f.connectTimeout = TIMEOUT_MS
        f.soTimeout = TIMEOUT_MS
        // 中文目录名/文件名：不加这条会乱码
        f.controlEncoding = "UTF-8"
        return try {
            f.connect(c.host, port)
            val reply = f.replyCode
            if (!org.apache.commons.net.ftp.FTPReply.isPositiveCompletion(reply)) {
                runCatching { f.disconnect() }
                return null to "连不上 ${c.host}:$port（FTP 响应码 $reply）"
            }
            f.enterLocalPassiveMode()
            if (!f.login(c.user, c.pass)) {
                val why = f.replyString?.trim().orEmpty()
                runCatching { f.disconnect() }
                return null to if (why.isBlank()) "账号或密码不对" else "账号或密码不对（$why）"
            }
            // 二进制：传 jar 必须，否则文件会被当成文本改写换行符而损坏
            f.setFileType(org.apache.commons.net.ftp.FTP.BINARY_FILE_TYPE)
            // 部分服务器需要显式开启 UTF-8，否则中文名是乱码
            runCatching { f.sendCommand("OPTS UTF8", "ON") }
            FtpConn(f) to null
        } catch (t: Throwable) {
            runCatching { f.disconnect() }
            null to "连接失败：${shortErr(t)}"
        }
    }

    private fun connectSftp(c: Conf, port: Int): net.schmizz.sshj.SSHClient {
        return net.schmizz.sshj.SSHClient().apply {
            connectTimeout = TIMEOUT_MS
            soTimeout = TIMEOUT_MS
            // 手机端没有维护 known_hosts 的场景，强校验只会让人连不上。
            // 这是"首次连接信任主机"的意思，不是关掉传输加密。
            addHostKeyVerifier { _, _, _ -> true }
            connect(c.host, port)
            authPassword(c.user, c.pass)
        }
    }

    /**
     * 连通性测试。
     * @return null 表示成功；否则返回一句人话原因
     */
    fun test(c: Conf): String? {
        if (c.host.isBlank()) return "还没填主机地址"
        val port = if (c.port > 0) c.port else defaultPort(c.kind)
        return when (c.kind) {
            Kind.FTP, Kind.FTPS -> {
                val (conn, err) = connectFtp(c, port)
                if (conn != null) {
                    conn.close()
                    null
                } else err
            }
            Kind.SFTP -> {
                var ssh: net.schmizz.sshj.SSHClient? = null
                try {
                    ssh = connectSftp(c, port)
                    return if (ssh.isAuthenticated) null else "认证失败（账号或密码不对）"
                } catch (t: Throwable) {
                    return "连接失败：${shortErr(t)}"
                } finally {
                    runCatching { ssh?.disconnect() }
                }
            }
        }
    }

    /**
     * 列出目录。
     *
     * 目录判定按各协议自己的类型字段来，
     * **不要**用"名字里有没有点"去猜（很多文件夹带点，很多文件不带）。
     */
    fun list(c: Conf, path: String): List<Entry> {
        val p = if (path.isBlank()) (c.root.ifBlank { "/" }) else path
        val port = if (c.port > 0) c.port else defaultPort(c.kind)
        return when (c.kind) {
            Kind.FTP, Kind.FTPS -> ftpList(c, port, p)
            Kind.SFTP -> sftpList(c, port, p)
        }
    }

    private fun ftpList(c: Conf, port: Int, path: String): List<Entry> {
        val (conn, err) = connectFtp(c, port)
        if (conn == null) {
            LogCenter.w("RemoteFs", "FTP 列出失败 $path：${err ?: "连接失败"}")
            return emptyList()
        }
        val f = conn.raw
        return try {
            // ⚠️ listFiles 在目录不存在时会返回 null，直接遍历会崩
            val arr = f.listFiles(path) ?: emptyArray()
            val out = mutableListOf<Entry>()
            for (e in arr) {
                val n = e.name
                if (n == "." || n == "..") continue
                out.add(
                    Entry(
                        name = n,
                        path = join(path, n),
                        isDir = e.isDirectory,
                        size = e.size
                    )
                )
            }
            // 目录排前面，其余按名字排，浏览起来顺手
            out.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase(Locale.ROOT) }))
        } catch (t: Throwable) {
            LogCenter.w("RemoteFs", "FTP 列出失败 $path：${t.message}")
            emptyList()
        } finally {
            conn.close()
        }
    }

    private fun sftpList(c: Conf, port: Int, path: String): List<Entry> {
        var ssh: net.schmizz.sshj.SSHClient? = null
        return try {
            ssh = connectSftp(c, port)
            ssh.newSFTPClient().use { sftp ->
                val out = mutableListOf<Entry>()
                for (e in sftp.ls(path)) {
                    val n = e.name
                    if (n == "." || n == "..") continue
                    out.add(
                        Entry(
                            name = n,
                            path = join(path, n),
                            isDir = e.type == net.schmizz.sshj.sftp.RemoteResourceInfo.Type.DIRECTORY,
                            size = e.attributes.size
                        )
                    )
                }
                out.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase(Locale.ROOT) }))
            }
        } catch (t: Throwable) {
            LogCenter.w("RemoteFs", "SFTP 列出失败 $path：${t.message}")
            emptyList()
        } finally {
            runCatching { ssh?.disconnect() }
        }
    }

    /**
     * 打开远程文件用于读取（算哈希、或下载到本地）。
     *
     * 返回的是**流**：jar 动辄几十 MB，一次性读进内存会 OOM。
     * 调用方必须 close（close 时连接一并收掉）。
     */
    fun open(c: Conf, path: String): InputStream? {
        val port = if (c.port > 0) c.port else defaultPort(c.kind)
        return when (c.kind) {
            Kind.FTP, Kind.FTPS -> {
                val (conn, _) = connectFtp(c, port) ?: return null
                val f = conn.raw
                try {
                    val s = f.retrieveFileStream(path) ?: run {
                        conn.close()
                        return null
                    }
                    // 关流时必须把连接也收掉，否则服务端会一直挂着这条数据连接
                    object : java.io.FilterInputStream(s) {
                        override fun close() {
                            runCatching { super.close() }
                            runCatching { f.completePendingCommand() }
                            conn.close()
                        }
                    }
                } catch (t: Throwable) {
                    conn.close()
                    null
                }
            }
            Kind.SFTP -> {
                var ssh: net.schmizz.sshj.SSHClient? = null
                var sftp: net.schmizz.sshj.sftp.SFTPClient? = null
                try {
                    ssh = connectSftp(c, port)
                    val sc = ssh.newSFTPClient()
                    sftp = sc
                    val rf = sc.open(path)
                    val s = rf.RemoteFileInputStream()
                    object : java.io.FilterInputStream(s) {
                        override fun close() {
                            runCatching { super.close() }
                            runCatching { rf.close() }
                            runCatching { sc.close() }
                            runCatching { ssh.disconnect() }
                        }
                    }
                } catch (t: Throwable) {
                    runCatching { sftp?.close() }
                    runCatching { ssh?.disconnect() }
                    null
                }
            }
        }
    }

    /** 常见模组/插件目录（FTP/SFTP 下多为这种布局，逐个试） */
    val DIR_CANDIDATES = listOf(
        "/mods", "/plugins", "/mod", "/plugin",
        "/data/mods", "/data/plugins",
        "/server/mods", "/server/plugins",
        "/home/container/mods", "/home/container/plugins",
        "/home/mods", "/home/plugins",
        "/"
    )

    /** 取目录里的 jar（只看名字，不下载内容） */
    fun jars(c: Conf, path: String): List<Entry> =
        list(c, path).filter { !it.isDir && it.name.endsWith(".jar", true) }

    private fun join(dir: String, name: String): String {
        val d = dir.trimEnd('/')
        return if (d.isEmpty()) "/$name" else "$d/$name"
    }

    private fun shortErr(t: Throwable): String {
        val m = t.message?.trim().orEmpty()
        return if (m.isBlank()) t.javaClass.simpleName else m.take(120)
    }
}
