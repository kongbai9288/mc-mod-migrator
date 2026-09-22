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
            val rel = UpdateChecker.latest(ctx) ?: return Result.success()
            val cur = currentVersion(ctx)
            if (UpdateChecker.isNewer(rel.tag, cur)) {
                notify(ctx, rel)
            }
            Result.success()
        } catch (t: Throwable) {
            Result.retry()
        }
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
