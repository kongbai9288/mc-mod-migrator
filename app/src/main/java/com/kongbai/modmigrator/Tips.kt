package com.kongbai.modmigrator

import android.widget.Toast

/**
 * 统一提示入口。
 *
 * 原先散落上百处 Toast（底部小黑条）：被内容挡住看不见、
 * 内容长就截断。这里统一收口到应用内卡片 [InAppNotice]。
 *
 * 只有拿不到 Activity（Service 上下文，浮层挂不上）时才退回系统小弹窗。
 */
object Tips {

    fun ok(ctx: android.content.Context?, msg: String) {
        if (!notice(ctx, "完成", msg, 2600)) toast(ctx, msg)
    }

    fun err(ctx: android.content.Context?, msg: String) {
        if (!notice(ctx, "出错了", msg, 6000)) toast(ctx, msg)
    }

    fun warn(ctx: android.content.Context?, msg: String) {
        if (!notice(ctx, "注意", msg, 4200)) toast(ctx, msg)
    }

    fun short(ctx: android.content.Context?, msg: String) {
        if (msg.length <= 16 && !msg.contains("\n")) {
            if (!notice(ctx, msg, "", 2600)) toast(ctx, msg)
            return
        }
        val head = msg.substringBefore('\n').trim()
        val rest = msg.substringAfter('\n', "").trim()
        if (!notice(ctx, head.ifBlank { "提示" }, rest.ifBlank { msg }, 3200)) toast(ctx, msg)
    }

    fun long(ctx: android.content.Context?, msg: String) {
        val head = msg.substringBefore('\n').trim()
        val rest = msg.substringAfter('\n', "").trim()
        if (!notice(ctx, head.ifBlank { "提示" }, rest.ifBlank { msg }, 5000)) toast(ctx, msg)
    }

    fun show(ctx: android.content.Context?, title: String, msg: String, ms: Long = 3200) {
        if (!notice(ctx, title, msg, ms)) toast(ctx, "$title：$msg")
    }

    /** @return true 表示已走应用内卡片，false 表示没有可用界面、需兜底 */
    private fun notice(ctx: android.content.Context?, title: String, msg: String, ms: Long): Boolean {
        val a = ctx as? android.app.Activity ?: return false
        if (a.isFinishing || a.isDestroyed) return false
        InAppNotice.show(title, msg, InAppNotice.Kind.INFO, ms)
        return true
    }

    private fun toast(ctx: android.content.Context?, msg: String) {
        val c = ctx ?: return
        runCatching { Toast.makeText(c.applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }
}
