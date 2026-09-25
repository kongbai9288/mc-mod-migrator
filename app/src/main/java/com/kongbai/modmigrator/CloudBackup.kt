package com.kongbai.modmigrator

import android.content.Context

/**
 * 云盘备份。
 *
 * 设计：不绑定任何特定网盘，也不要求登录。
 * 你自己打开云盘网页、往里上传一次文件，把拿到的「上传地址/分享链接」
 * 贴进设置里，之后每次备份都往这个地址传。
 *
 * 这样做的原因：
 *   - 各家网盘的 API 各不相同，且大多要求 OAuth 授权，
 *     内置任何一家都会让不用那家的人白白多一个授权步骤
 *   - 你自己的网盘，凭据不应该经过本应用
 *   - 换网盘只需要改一个地址，不用改代码
 *
 * 自动备份间隔可调，关掉就是完全手动。
 */
object CloudBackup {

    /** 间隔选项：小时。0 = 关闭自动备份 */
    val INTERVALS = listOf(
        0 to "关闭（只手动备份）",
        6 to "每 6 小时",
        12 to "每 12 小时",
        24 to "每天",
        72 to "每 3 天",
        168 to "每周"
    )

    fun uploadUrl(ctx: Context): String =
        Prefs.get(ctx).getString(K.CLOUD_UPLOAD_URL, "") ?: ""

    fun setUploadUrl(ctx: Context, url: String) {
        Prefs.get(ctx).edit().putString(K.CLOUD_UPLOAD_URL, url.trim()).apply()
    }

    fun intervalHours(ctx: Context): Int =
        Prefs.get(ctx).getInt(K.AUTO_BACKUP_HOURS, 24)

    fun setIntervalHours(ctx: Context, h: Int) {
        Prefs.get(ctx).edit().putInt(K.AUTO_BACKUP_HOURS, h).apply()
        // 改完间隔要立刻重新注册，否则新间隔要等旧任务结束才生效
        schedule(ctx)
    }

    fun enabled(ctx: Context): Boolean =
        uploadUrl(ctx).isNotBlank() && intervalHours(ctx) > 0

    /**
     * 最近一次备份的时间戳（毫秒）。
     */
    fun lastBackupAt(ctx: Context): Long =
        Prefs.get(ctx).getLong("last_backup_at", 0L)

    fun markBackedUp(ctx: Context) {
        Prefs.get(ctx).edit().putLong("last_backup_at", System.currentTimeMillis()).apply()
    }

    /** 是否到了该备份的时间 */
    fun due(ctx: Context): Boolean {
        if (!enabled(ctx)) return false
        val h = intervalHours(ctx)
        val last = lastBackupAt(ctx)
        if (last <= 0L) return true
        return System.currentTimeMillis() - last >= h * 3600L * 1000L
    }

    /**
     * 注册/取消自动备份的周期任务。
     *
     * 之前这个方法根本不存在——`intervalHours()`、`due()` 都写好了，
     * 但没有任何地方把它们接起来，所以自动备份**从不执行**。
     *
     * WorkManager 的周期任务最小间隔是 15 分钟，
     * 我们选的都是小时级（6/12/24/72/168），不会触发这个下限。
     */
    fun schedule(ctx: Context) {
        try {
            val wm = androidx.work.WorkManager.getInstance(ctx)
            val h = intervalHours(ctx)
            if (h <= 0 || uploadUrl(ctx).isBlank()) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val req = androidx.work.PeriodicWorkRequestBuilder<CloudBackupWorker>(
                h.toLong(), java.util.concurrent.TimeUnit.HOURS
            ).build()
            wm.enqueueUniquePeriodicWork(
                WORK_NAME, androidx.work.ExistingPeriodicWorkPolicy.UPDATE, req
            )
        } catch (t: Throwable) {
            Err.ignore(t, "注册云盘自动备份")
        }
    }

    private const val WORK_NAME = "mm_cloud_backup"

    /**
     * 执行一次备份：打包当前工作目录里的用户数据，POST 到用户填的上传地址。
     *
     * 上传地址是用户自己在云盘网页上拿到的（本应用不持有任何网盘凭据），
     * 所以这里只做最通用的 multipart/form-data 上传。
     * 成功与否都返回一句能直接显示给用户的话。
     */
    fun run(ctx: Context): String {
        val url = uploadUrl(ctx)
        if (url.isBlank()) return "还没设置云盘上传地址"

        val zip = java.io.File(ctx.cacheDir, "backup.zip")
        return try {
            // 打包：收藏、标记链接、回收站清单这些用户数据
            val dataDir = WorkDir.data(ctx)
            val packed = BundleManager.zipData(ctx, dataDir, zip)
            if (!packed || !zip.exists() || zip.length() <= 0L) {
                return "没有可备份的数据"
            }
            val ok = Http.postFile(url, zip, "backup.zip")
            if (ok) {
                "备份成功（${zip.length() / 1024}KB）"
            } else {
                "备份失败：上传地址没响应（可到设置里重新拿一次）"
            }
        } catch (t: Throwable) {
            "备份失败：${Http.describeError(t)}"
        } finally {
            runCatching { zip.delete() }
        }
    }

    /** 给用户的状态描述 */
    fun describe(ctx: Context): String {
        val url = uploadUrl(ctx)
        if (url.isBlank()) {
            return "还没设置云盘上传地址。\n" +
                "在设置里填一次（去云盘网页上传一个文件就能拿到），之后自动备份都用它。"
        }
        val h = intervalHours(ctx)
        val freq = if (h <= 0) "已关闭自动备份" else "每 $h 小时自动备份一次"
        val last = lastBackupAt(ctx)
        val lastTxt = if (last <= 0L) "尚未备份过" else {
            val s = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
            "上次备份：${s.format(java.util.Date(last))}"
        }
        return "上传地址：${url.take(60)}\n$freq\n$lastTxt"
    }
}
