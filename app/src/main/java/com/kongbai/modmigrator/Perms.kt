package com.kongbai.modmigrator

import android.content.Context
import android.content.Intent
import android.content.UriPermission
import android.net.Uri
import android.os.Build

/**
 * 统一授权管理。
 *
 * 之前的问题：每个页面各自调 takePersistableUriPermission()，
 * 换一次目录就多占一份配额，而系统对持久化授权有硬上限——
 * Android 10 是 128 条，Android 11+ 放宽到 512 条。
 * 一旦触顶，新的 take() 会静默失败，表现就是「授权了但读不了」，
 * 于是用户被反复要求重新授权。
 *
 * 这里改成：
 *   1. 所有授权都走同一个入口，先检查是否已持有，避免重复占用
 *   2. 接近上限时自动释放最旧的、且已不再使用的授权
 *   3. 提供统一的「已授权目录」视图，用户能看到授权了什么
 */
object Perms {

    /** 系统上限（保守按 128 算，Android 11+ 实际是 512） */
    private const val LIMIT = 128

    /** 剩余少于这个数就开始回收 */
    private const val KEEP_FREE = 8

    private const val FLAGS =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /** 当前已持久化的授权 */
    fun granted(ctx: Context): List<UriPermission> =
        try {
            ctx.contentResolver.persistedUriPermissions
        } catch (t: Throwable) {
            emptyList()
        }

    /** 是否已持有某个 URI 的持久化授权 */
    fun has(ctx: Context, uriStr: String?): Boolean {
        if (uriStr.isNullOrBlank()) return false
        val target = Uri.parse(uriStr)
        return granted(ctx).any { it.uri == target }
    }

    /**
     * 申请并持久化目录授权。
     * @return 是否成功持有（已经持有也算成功，不会重复占用）
     */
    fun take(ctx: Context, treeUri: Uri): Boolean {
        // 已经持有就直接复用，不重复占配额
        if (has(ctx, treeUri.toString())) return true
        return try {
            // 先腾地方，避免触顶后静默失败
            trim(ctx)
            val flags = FLAGS
            ctx.contentResolver.takePersistableUriPermission(treeUri, flags)
            has(ctx, treeUri.toString())
        } catch (t: Throwable) {
            false
        }
    }

    /** 释放某个授权 */
    fun release(ctx: Context, uriStr: String?) {
        if (uriStr.isNullOrBlank()) return
        try {
            ctx.contentResolver.releasePersistableUriPermission(
                Uri.parse(uriStr), FLAGS
            )
        } catch (t: Throwable) {
        }
    }

    /** 当前仍在使用的授权（即设置里存着的那些 URI） */
    private fun inUse(ctx: Context): Set<String> {
        val p = Prefs.get(ctx)
        val keys = listOf(
            K.WORKDIR_URI, K.SRC_URI, K.DST_URI, K.SCAN_ROOT
        )
        val out = LinkedHashSet<String>()
        for (k in keys) {
            val v = p.getString(k, null)
            if (!v.isNullOrBlank()) out.add(v)
        }
        return out
    }

    /**
     * 配额回收：把「已授权但配置里已经不用」的那些释放掉。
     * 只有在不释放就会触顶时才动手，避免影响用户体验。
     */
    fun trim(ctx: Context) {
        val list = granted(ctx)
        if (list.size < LIMIT - KEEP_FREE) return
        val used = inUse(ctx)
        // 按授权时间升序，先释放最旧的
        val stale = list
            .filter { it.uri.toString() !in used }
            .sortedBy { it.persistedTime }
        val need = list.size - (LIMIT - KEEP_FREE) + 1
        for (p in stale.take(need)) {
            release(ctx, p.uri.toString())
        }
    }

    /**
     * 校验某个已存的 URI 是否还可用。
     * 不只查权限列表，还真的试着列一下目录内容——
     * 因为目录可能被用户删除或改名，权限还在但内容已经没了。
     */
    fun usable(ctx: Context, uriStr: String?): Boolean {
        if (uriStr.isNullOrBlank()) return false
        if (!has(ctx, uriStr)) return false
        return try {
            val d = Fs.tree(ctx, uriStr)
            d != null && d.isDirectory
        } catch (t: Throwable) {
            false
        }
    }

    /** 是否有「所有文件访问」权限（Android 11+） */
    fun allFiles(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            try {
                android.os.Environment.isExternalStorageManager()
            } catch (t: Throwable) {
                false
            }

    /**
     * 把已授权目录整理成人能看懂的列表，给设置页展示用。
     */
    fun describe(ctx: Context): List<Pair<String, String>> {
        val p = Prefs.get(ctx)
        val out = ArrayList<Pair<String, String>>()
        val map = listOf(
            "工作目录" to K.WORKDIR_URI,
            "迁移前的版本" to K.SRC_URI,
            "迁移后的版本" to K.DST_URI,
            "扫描根目录" to K.SCAN_ROOT
        )
        for ((label, key) in map) {
            val v = p.getString(key, null)
            if (v.isNullOrBlank()) continue
            val ok = usable(ctx, v)
            val name = try {
                Uri.parse(v).lastPathSegment?.substringAfterLast(':') ?: v
            } catch (t: Throwable) {
                v
            }
            out.add(Pair(label, if (ok) "$name（正常）" else "$name（已失效，需重新选择）"))
        }
        val n = granted(ctx).size
        out.add(Pair("授权占用", "$n / $LIMIT 条"))
        return out
    }
}
