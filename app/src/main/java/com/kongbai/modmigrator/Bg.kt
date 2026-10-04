package com.kongbai.modmigrator

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 全局共享的后台执行器。
 *
 * ## 为什么需要这个
 * 之前每个 Fragment 都自己 `Executors.newSingleThreadExecutor()`，
 * 而**没有任何地方 shutdown**。主界面每次切 tab 都是 new 一个 Fragment 再 replace，
 * 旧 Fragment 被销毁、它那个线程池却还活着——线程里排队的 Runnable
 * 是 Fragment 的内部 lambda，持有 Fragment，进而持有整个 View 树和 Activity。
 *
 * 于是**每切一次 tab 就泄漏一份**。切个十几二十次，堆就被这些
 * 无法回收的 View 树吃满，表现就是页面变白、应用卡顿。
 *
 * 这里改成全局共用一组线程：不管切多少次、new 多少个 Fragment，
 * 线程数恒定，也不再持有任何 Fragment 的引用。
 *
 * ## 关于 Runnable 仍持有 Fragment
 * 共享线程不解决这个问题，所以每个 Fragment 仍要在
 * `onDestroyView()` 里 `handler.removeCallbacksAndMessages(null)`，
 * 并用一个"我还活着吗"的标志跳过回调（多数 Fragment 已用 `isAdded` 判断）。
 */
object Bg {

    /** 主线程 handler，全程序共用一个 */
    val ui: Handler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * IO / 网络类任务池。
     * 定长 4 条：这类任务多是等网络，多一点并发不会真占满 CPU，
     * 但也不至于开太多把内存撑爆。
     */
    val io: ExecutorService by lazy {
        Executors.newFixedThreadPool(4) { r ->
            Thread(r, "mm-io").apply { isDaemon = true }
        }
    }

    /**
     * 单线程串行池，给要求按顺序执行的任务（比如写同一个文件）。
     * 之前每个 Fragment 各建一个，实际根本用不上那么多串行队列。
     */
    val serial: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "mm-serial").apply { isDaemon = true }
        }
    }

    /** 投一个后台任务 */
    fun run(block: () -> Unit) = io.execute(block)

    /** 回到主线程执行；调用方已销毁时什么都不做 */
    fun post(block: () -> Unit) = ui.post(block)
}
