package com.kongbai.modmigrator

import android.app.NotificationManager
import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** 每天检查一次更新，有新版本发通知 */
class UpdateWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        if (Prefs.get(ctx).getBoolean(K.OFFLINE, false)) return Result.success()
        if (!Prefs.get(ctx).getBoolean(K.UPDATE_CHECK, true)) return Result.success()
        return try {
            val rel = UpdateChecker.latest(ctx)
            if (rel == null) {
                val why = UpdateChecker.lastError.ifBlank { "未知原因" }
                saveState(ctx, "", "没能取到版本信息：$why")
                return Result.success()
            }
            val cur = currentVersion(ctx)
            saveState(ctx, rel.tag, rel.notes)
            if (UpdateChecker.isNewer(rel.tag, cur)) {
                notify(ctx, rel)
            }
            Result.success()
        } catch (t: Throwable) {
            Result.retry()
        }
    }

    /** 把最近一次检查结果写下来，设置页可以立刻展示，不用等下次定时 */
    private fun saveState(ctx: Context, tag: String, notes: String) {
        Prefs.get(ctx).edit()
            .putString(K.LAST_UPDATE_TAG, tag)
            .putString(K.LAST_UPDATE_NOTES, notes)
            .putLong(K.LAST_UPDATE_AT, System.currentTimeMillis())
            .apply()
    }

    private fun currentVersion(ctx: Context): String {
        return try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
        } catch (t: Throwable) {
            "0"
        }
    }

    private fun notify(ctx: Context, rel: UpdateChecker.Release) {
        Notifier.show(ctx, "有新版本 ${rel.tag}", rel.name.ifBlank { "点击查看详情" })
    }

    companion object {
        private const val NAME = "update_check"

        fun schedule(ctx: Context) {
            val c = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(c)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP, req
            )
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(NAME)
        }
    }
}
