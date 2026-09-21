package com.kongbai.modmigrator

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class SyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val c = applicationContext
        if (!Prefs.get(c).getBoolean(K.AUTO_SYNC, false)) return Result.success()
        return try {
            val msg = SyncManager.upload(c)
            Notifier.show(c, "自动同步", msg)
            Result.success()
        } catch (t: Throwable) {
            Result.retry()
        }
    }
}
