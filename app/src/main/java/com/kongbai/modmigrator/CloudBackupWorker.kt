package com.kongbai.modmigrator

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * 云盘自动备份的后台任务。
 *
 * 之前 `CloudBackup` 里有 `intervalHours()` / `due()` 这些判断逻辑，
 * 但**没有任何地方调度它**——自动备份间隔在设置里能选，选完永远不执行。
 * 这属于"有代码没接线"：界面能操作，功能实际不工作。
 *
 * 这里补上真正的执行体，由 `CloudBackup.schedule()` 按所选间隔注册。
 */
class CloudBackupWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val c = applicationContext
        return try {
            if (!CloudBackup.enabled(c)) return Result.success()
            // due() 判断距离上次备份是否已超过设定间隔。
            // 系统可能因省电策略把周期任务延后，这一层判断能保证
            // "提前触发时不会重复备份"。
            if (!CloudBackup.due(c)) return Result.success()

            val msg = CloudBackup.run(c)
            CloudBackup.markBackedUp(c)
            Notifier.show(c, "自动备份", msg)
            Result.success()
        } catch (t: Throwable) {
            Err.ignore(t, "云盘自动备份")
            // 网络类失败才重试；配置问题重试也没用，直接结束
            Result.retry()
        }
    }
}
