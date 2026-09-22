package com.kongbai.modmigrator

import androidx.fragment.app.Fragment
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.util.concurrent.ExecutorService

/**
 * Fragment 安全工具。
 *
 * 后台任务跑完时 Fragment 可能已经 detach（用户按了返回、切了 tab），
 * 这时再调 requireContext() 会抛 IllegalStateException 直接崩。
 * 这里统一包一层：拿不到上下文就安静返回，不崩、不弹错。
 */

/** 后台里要用上下文时先取这个，拿不到就 null，由调用方决定要不要继续 */
val Fragment.safeCtx: android.content.Context?
    get() = try {
        context
    } catch (t: Throwable) {
        null
    }

/** 回到主线程执行；Fragment 已 detach 则什么都不做 */
fun Fragment.safePost(handler: Handler, block: () -> Unit) {
    handler.post {
        if (!isAdded) return@post
        try {
            block()
        } catch (t: Throwable) {
            // 界面已经不在了，静默吞掉，避免崩溃
        }
    }
}

/** 在后台执行；异常被捕获并安全提示，不会让进程崩掉 */
fun Fragment.safeBg(exec: ExecutorService, handler: Handler, block: (android.content.Context) -> Unit) {
    val ctx = safeCtx ?: return
    exec.execute {
        try {
            block(ctx)
        } catch (t: Throwable) {
            safePost(handler) {
                safeCtx?.let { Toast.makeText(it, "出错：${t.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }
}

/** 安全的 Toast：上下文没了就不弹 */
fun Fragment.safeToast(handler: Handler, msg: String) {
    safePost(handler) {
        safeCtx?.let { Toast.makeText(it, msg, Toast.LENGTH_SHORT).show() }
    }
}

fun mainHandler(): Handler = Handler(Looper.getMainLooper())
