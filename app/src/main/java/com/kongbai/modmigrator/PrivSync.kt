package com.kongbai.modmigrator

import android.content.Context
import android.content.pm.PackageManager
import java.io.File

/**
 * 读取启动器的**私有目录**（Android/data/…）。
 *
 * ⚠️ 背景：Android 11 起第三方 App 进不去别的 App 的私有目录；
 * Android 14 起连「所有文件访问」权限也进不去。
 * 所以私有目录只有两条路能拿到：
 *   1. 用户装了同步器（Shizuku），由它以 adb/root 身份代读
 *   2. 让启动器自己导入（启动器写自己的目录不受限）
 *
 * 这里做的是第 1 条，并且**先试普通文件读写**：
 * 有「所有文件访问」权限、或系统版本还允许时，直接 File 就能读，
 * 不必惊动同步器。读不到才走同步器的 shell。
 *
 * 依赖 Shizuku（Apache-2.0）。没装同步器时不报错，只是读不了私有目录。
 */
object PrivSync {

    const val PKG = "moe.shizuku.privileged.api"
    const val HOME = "https://shizuku.rikka.app/download/"

    /** 权限请求码，外面拿它比对 onRequestPermissionResult */
    const val REQ = 9401

    data class Status(
        /** 装了同步器吗 */
        val installed: Boolean,
        /** 同步器在跑吗 */
        val running: Boolean,
        /** 已经授权给本 App 吗 */
        val granted: Boolean
    ) {
        /** 现在能不能用同步器读 */
        val ready: Boolean get() = running && granted

        fun text(ctx: Context): String = when {
            !installed -> "没装同步器。私有目录读不到，可以在启动器里把游戏目录改到公共目录，或让它自己导入整合包。"
            !running -> "同步器装了但没启动。打开它，按里面的说明激活（adb 或 root）后再回来。"
            !granted -> "同步器在跑，但还没授权给本应用。点「请求授权」，在弹出的框里允许。"
            else -> "同步器已就绪，可以读私有目录。"
        }
    }

    fun status(ctx: Context): Status {
        val ins = try {
            ctx.packageManager.getPackageInfo(PKG, 0) != null
        } catch (t: Throwable) {
            false
        }
        if (!ins) return Status(false, false, false)
        val run = try {
            rikka.shizuku.Shizuku.pingBinder()
        } catch (t: Throwable) {
            false
        }
        val grant = if (run) {
            try {
                rikka.shizuku.Shizuku.checkPermission() == PackageManager.PERMISSION_GRANTED
            } catch (t: Throwable) {
                false
            }
        } else false
        return Status(true, run, grant)
    }

    /** 请求同步器授权。返回 false 表示同步器不可用（没装/没跑） */
    fun requestPermission(): Boolean {
        return try {
            if (!rikka.shizuku.Shizuku.pingBinder()) return false
            if (rikka.shizuku.Shizuku.checkPermission() == PackageManager.PERMISSION_GRANTED) return true
            rikka.shizuku.Shizuku.requestPermission(REQ)
            true
        } catch (t: Throwable) {
            Err.ignore(t, "请求同步器授权")
            false
        }
    }

    /** 在 Application 里挂上结果监听（只挂一次） */
    fun bindResultListener(onResult: (Boolean) -> Unit) {
        try {
            rikka.shizuku.Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
                if (requestCode == REQ) onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        } catch (t: Throwable) {
            Err.ignore(t, "挂同步器授权回调")
        }
    }

    /** 结果是不是我们这次请求 */
    fun isOurRequest(code: Int): Boolean = code == REQ

    /** 该目录存在且能读吗。先试普通 File，不行再走同步器 */
    fun exists(path: String): Boolean {
        if (path.isBlank()) return false
        val f = File(path)
        if (f.isDirectory && (f.canRead() || f.list() != null)) return true
        val c = Prefs.appCtx() ?: return false
        if (!status(c).ready) return false
        return sh("test -d ${q(path)} && echo 1").second.contains("1")
    }

    /**
     * 把私有目录整棵复制到公共目录（暂存区），之后按普通目录处理。
     *
     * 先试普通 File 复制；失败再用同步器的 shell 复制。
     * 复制失败返回 false，**不会**删除已有目标 —— 半截的暂存区留着便于排查。
     */
    fun copyOut(src: String, dst: String, log: (String) -> Unit): Boolean {
        if (src.isBlank() || dst.isBlank()) return false
        val s = File(src)
        val d = File(dst)
        if (s.isDirectory) {
            log("直接读取：$src")
            return try {
                d.mkdirs()
                copyTree(s, d, log)
            } catch (t: Throwable) {
                Err.ignore(t, "直接复制私有目录")
                false
            }
        }
        val c = Prefs.appCtx()
        if (c == null || !status(c).ready) {
            log("读不到：$src（没装同步器或未授权）")
            return false
        }
        log("用同步器读取：$src")
        d.mkdirs()
        val (code, out) = sh("cp -a ${q(src)}/. ${q(dst)}/ 2>&1; echo EXIT=$?")
        log(out.trim().takeLast(400))
        return d.list()?.isNotEmpty() == true && !out.contains("EXIT=" + "1")
    }

    private fun copyTree(s: File, d: File, log: (String) -> Unit) {
        val kids = s.listFiles() ?: return
        for (k in kids) {
            val t = File(d, k.name)
            if (k.isDirectory) {
                t.mkdirs()
                copyTree(k, t, log)
            } else {
                k.inputStream().use { i -> t.outputStream().use { o -> i.copyTo(o) } }
            }
        }
        log("已复制 ${kids.size} 项 → ${d.absolutePath}")
    }

    private fun sh(cmd: String): Pair<Int, String> {
        return try {
            val p = rikka.shizuku.Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val out = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            code to out
        } catch (t: Throwable) {
            Err.ignore(t, "同步器执行 shell")
            -1 to (t.message ?: "")
        }
    }

    private fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
