package com.kongbai.modmigrator

import android.content.Context
import androidx.documentfile.provider.DocumentFile

/**
 * 批量模组操作：升级 / 删除 / 下载。
 *
 * 之前每个模组只能一个个点，装几十个模组时非常折磨。
 * 这里统一成「勾选 → 批量执行」，并且：
 *  - 每个任务独立 try，一个失败不影响其余
 *  - 实时回报进度（第几个 / 共几个 / 成功了几个）
 *  - 删除走回收站，可撤销
 */
object BatchModOps {

    /** 单个任务的结果 */
    data class Result(
        val name: String,
        val ok: Boolean,
        val msg: String = ""
    ) {
        companion object {
            fun ok(name: String, msg: String = "") = Result(name, true, msg)
            fun fail(name: String, msg: String) = Result(name, false, msg)
        }
    }

    /** 批量进度回调：当前第几个、总共几个、当前在做什么 */
    fun interface Progress {
        fun on(idx: Int, total: Int, doing: String)
    }

    /**
     * 批量删除（进回收站，可还原）。
     * @return 每个文件的处理结果
     */
    fun delete(
        ctx: Context,
        files: List<DocumentFile>,
        onProgress: Progress? = null
    ): List<Result> {
        val out = ArrayList<Result>()
        files.forEachIndexed { i, f ->
            val name = f.name ?: return@forEachIndexed
            onProgress?.on(i + 1, files.size, "删除 $name")
            val ok = try {
                Trash.moveToTrash(ctx, f)
            } catch (t: Throwable) {
                false
            }
            out.add(
                if (ok) Result.ok(name, "已进回收站")
                else Result.fail(name, "删除失败（目录可能只读）")
            )
        }
        return out
    }

    /**
     * 批量启用 / 禁用。
     * @param disabled true=禁用，false=启用
     */
    fun toggle(
        files: List<DocumentFile>,
        disabled: Boolean,
        onProgress: Progress? = null
    ): List<Result> {
        val out = ArrayList<Result>()
        files.forEachIndexed { i, f ->
            val name = f.name ?: return@forEachIndexed
            onProgress?.on(i + 1, files.size, if (disabled) "禁用 $name" else "启用 $name")
            val r = if (disabled) ModToggle.disable(f) else ModToggle.enable(f)
            out.add(
                if (r != null) Result.ok(name, if (disabled) "已禁用" else "已启用")
                else Result.fail(name, "改名失败")
            )
        }
        return out
    }

    /**
     * 批量下载（按 url → 文件名 的映射）。
     *
     * 之前注释写着"并发数受设置里的下载并发限制"，但**实现是完全串行的**
     * —— `forEachIndexed` 一个一个下，而且声明的 `out`（同步列表）和 `done`
     * 两个变量根本没用上。装 20 个模组就是 20 次排队等待。
     * 现在改成真正的多文件并发。
     *
     * 并发度怎么定：`Downloader.download` 内部**已经会按 DOWNLOAD_PARALLEL
     * 把一个文件切成多块并发**。如果这里再开同样多的文件并发，
     * 总连接数 = 文件数 × 块数，很容易把网打满、反而全变慢。
     * 所以文件并发取 DOWNLOAD_PARALLEL 的一半，上限 4。
     */
    fun download(
        ctx: Context,
        dir: DocumentFile,
        tasks: List<Pair<String, String>>,   // url to name
        onProgress: Progress? = null
    ): List<Result> {
        val total = tasks.size
        if (total == 0) return emptyList()

        val results = java.util.Collections.synchronizedList(ArrayList<Result>(total))
        val finished = java.util.concurrent.atomic.AtomicInteger(0)

        val configured = Prefs.get(ctx).getInt(K.DOWNLOAD_PARALLEL, 3).coerceIn(1, 8)
        val fileParallel = (configured / 2).coerceIn(1, 4).coerceAtMost(total)

        val pool = java.util.concurrent.Executors.newFixedThreadPool(fileParallel)
        return try {
            val futures = ArrayList<java.util.concurrent.Future<*>>()
            for ((url, name) in tasks) {
                futures.add(
                    pool.submit {
                        val f = try {
                            Downloader.download(ctx, url, dir, name)
                        } catch (t: Throwable) {
                            null
                        }
                        results.add(
                            if (f != null) Result.ok(name, "已下载")
                            else Result.fail(name, "下载失败")
                        )
                        val n = finished.incrementAndGet()
                        onProgress?.on(n, total, "下载 $name")
                    }
                )
            }
            // 等全部结束；中断时要取消，不能把线程挂着
            try {
                for (fu in futures) fu.get()
            } catch (t: Throwable) {
                for (fu in futures) fu.cancel(true)
            }
            // 按原任务顺序输出，结果顺序稳定
            val byName = results.associateBy { it.name }
            tasks.map { (_, name) ->
                byName[name] ?: Result.fail(name, "未执行")
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /** 把结果汇总成一段话 */
    fun summary(results: List<Result>, verb: String): String {
        val ok = results.count { it.ok }
        val bad = results.size - ok
        return buildString {
            append("$verb 完成：$ok / ${results.size}")
            if (bad > 0) {
                append("\n\n失败的：\n")
                results.filter { !it.ok }.take(8).forEach {
                    append("  · ${it.name}：${it.msg}\n")
                }
                if (bad > 8) append("  …等 $bad 个\n")
            }
        }
    }
}
