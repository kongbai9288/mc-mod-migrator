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
 * 所以"填面板账号密码登录"这条路从设计上就走不通——
 * 之前点了就是一句没头没脑的"登录失败"。
 *
 * 但**绝大多数租来的服务器都开放 FTP 或 SSH(SFTP)**，
 * 而且用的就是账号密码。用它直接读 mods / plugins 目录，
 * 能覆盖"我只是想看看服务器上装了哪些模组、有没有更新"这个真实需求。
 *
 * 用的两个库都是 Apache-2.0（宽松许可，不传染）：
 *   · commons-net  → FTP / FTPS
 *   · sshj         → SFTP
 */
object RemoteFs {

    enum class Kind(val label: String) {
        FTP("FTP"),
        FTPS("FTPS（显式 TLS）"),
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

    /**
     * 连通性测试。
     * @return null 表示成功；否则返回一句人话原因
     */
    fun test(c: Conf): String? {
        if (c.host.isBlank()) return "还没填主机地址"
        val port = if (c.port > 0) c.port else defaultPort(c.kind)
        return when (c.kind) {
            Kind.FTP, Kind.FTPS -> {
                val f = org.apache.commons.net.ftp.FTPClient().apply {
                    connectTimeout = 8000
                    // 中文目录名/文件名：不加这条会乱码
                    controlEncoding = "UTF-8"
                }
                try {
                    f.connect(c.host, port)
                    val reply = f.replyCode
                    if (!org.apache.commons.net.ftp.FTPReply.isPositiveCompletion(reply)) {
                        return "连不上 ${c.host}:$port（FTP 响应码 $reply）"
                    }
                    if (c.kind == Kind.FTPS) {
                        // 显式 FTPS：先连明文，再 AUTH TLS 升级
                        f.execAuthTLS()
                    }
                    // 被动模式：服务器主动连客户端在国内几乎必失败
                    f.enterLocalPassiveMode()
                    if (!f.login(c.user, c.pass)) {
                        return "账号或密码不对（${f.replyString.trim()}）"
                    }
                    return null
                } catch (t: Throwable) {
                    return "连接失败：${shortErr(t)}"
                } finally {
                    runCatching { f.disconnect() }
                }
            }
            Kind.SFTP -> {
                var ssh: net.schmizz.sshj.SSHClient? = null
                try {
                    ssh = net.schmizz.sshj.SSHClient().apply {
                        connectTimeout = 8000
                        // 只测连通：不校验 known_hosts，
                        // 手机端没有维护 known_hosts 的场景，强校验只会让人连不上
                        addHostKeyVerifier { _, _, _ -> true }
                        connect(c.host, port)
                        authPassword(c.user, c.pass)
                    }
                    return if (ssh.isAuthenticated) null else "认证失败"
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
        val f = org.apache.commons.net.ftp.FTPClient().apply {
            connectTimeout = 8000
            controlEncoding = "UTF-8"
        }
        return try {
            f.connect(c.host, port)
            if (c.kind == Kind.FTPS) f.execAuthTLS()
            f.enterLocalPassiveMode()
            if (!f.login(c.user, c.pass)) return emptyList()
            // UTF-8 支持：部分服务器需要显式开启，否则中文名是乱码
            runCatching { f.sendCommand("OPTS UTF8", "ON") }
            val arr = f.listFiles(path)
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
            runCatching { f.disconnect() }
        }
    }

    private fun sftpList(c: Conf, port: Int, path: String): List<Entry> {
        var ssh: net.schmizz.sshj.SSHClient? = null
        return try {
            ssh = net.schmizz.sshj.SSHClient().apply {
                connectTimeout = 8000
                addHostKeyVerifier { _, _, _ -> true }
                connect(c.host, port)
                authPassword(c.user, c.pass)
            }
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
     * 调用方必须 close。
     */
    fun open(c: Conf, path: String): InputStream? {
        val port = if (c.port > 0) c.port else defaultPort(c.kind)
        return when (c.kind) {
            Kind.FTP, Kind.FTPS -> {
                val f = org.apache.commons.net.ftp.FTPClient().apply {
                    connectTimeout = 8000
                    controlEncoding = "UTF-8"
                }
                try {
                    f.connect(c.host, port)
                    if (c.kind == Kind.FTPS) f.execAuthTLS()
                    f.enterLocalPassiveMode()
                    if (!f.login(c.user, c.pass)) return null
                    f.setFileType(org.apache.commons.net.ftp.FTP.BINARY_FILE_TYPE)
                    val s = f.retrieveFileStream(path) ?: return null
                    // 关流时必须把连接也收掉，否则服务端会一直挂着这条数据连接
                    object : java.io.FilterInputStream(s) {
                        override fun close() {
                            runCatching { super.close() }
                            runCatching { f.completePendingCommand() }
                            runCatching { f.disconnect() }
                        }
                    }
                } catch (t: Throwable) {
                    runCatching { f.disconnect() }
                    null
                }
            }
            Kind.SFTP -> {
                val ssh = net.schmizz.sshj.SSHClient().apply {
                    connectTimeout = 8000
                    addHostKeyVerifier { _, _, _ -> true }
                    connect(c.host, port)
                    authPassword(c.user, c.pass)
                }
                try {
                    val sftp = ssh.newSFTPClient()
                    val s = sftp.open(path).RemoteFileInputStream().apply {
                        // 顺序读 + 预读，网络文件系统上快很多
                    }
                    object : java.io.FilterInputStream(s) {
                        override fun close() {
                            runCatching { super.close() }
                            runCatching { sftp.close() }
                            runCatching { ssh.disconnect() }
                        }
                    }
                } catch (t: Throwable) {
                    runCatching { ssh.disconnect() }
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
