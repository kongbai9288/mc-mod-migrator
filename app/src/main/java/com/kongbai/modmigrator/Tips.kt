package com.kongbai.modmigrator

import android.content.Context
import android.widget.Toast

/**
 * 统一提示入口。
 *
 * 项目里原先散落上百处 `Toast.makeText(...)`，底部小黑条有两个老毛病：
 * 被内容挡住看不见、内容长就截断。这里统一收口到应用内卡片
 * （[InAppNotice]，从顶部落下）。
 *
 * 兜底：只有拿不到 Activity（Service / Application 上下文，浮层挂不上）
 * 时才退回系统小弹窗，避免整条提示丢掉。能挂浮层就绝不重复弹两次。
 */
object Tips {

    @JvmStatic
    fun ok(ctx: Context?, msg: String) {
        if (!notice(ctx, "完成", msg, 2600)) toast(ctx, msg)
    }

    @JvmStatic
    fun err(ctx: Context?, msg: String) {
        if (!notice(ctx, "出错了", msg, 6000)) toast(ctx, msg)
    }

    @JvmStatic
    fun warn(ctx: Context?, msg: String) {
        if (!notice(ctx, "注意", msg, 4200)) toast(ctx, msg)
    }

    /** 一行短提示 */
    @JvmStatic
    fun short(ctx: Context?, msg: String) {
        if (msg.length <= 16 && !msg.contains("\n")) {
            if (!notice(ctx, msg, "", 2600)) toast(ctx, msg)
            return
        }
        val head = msg.substringBefore('\n').trim()
        val rest = msg.substringAfter('\n', "").trim()
        if (!notice(ctx, head.ifBlank { "提示" }, rest.ifBlank { msg }, 3200)) toast(ctx, msg)
    }

    @JvmStatic
    fun long(ctx: Context?, msg: String) {
        val head = msg.substringBefore('\n').trim()
        val rest = msg.substringAfter('\n', "").trim()
        if (!notice(ctx, head.ifBlank { "提示" }, rest.ifBlank { msg }, 5000)) toast(ctx, msg)
    }

    /** 带明确标题的提示，正文长的时候用它 */
    @JvmStatic
    fun show(ctx: Context?, title: String, msg: String, ms: Long = 3200) {
        if (!notice(ctx, title, msg, ms)) toast(ctx, "$title：$msg")
    }

    /** @return true 表示已走应用内卡片，false 表示没有可用界面、需兜底 */
    private fun notice(ctx: Context?, title: String, msg: String, ms: Long): Boolean {
        val a = ctx as? android.app.Activity ?: return false
        if (a.isFinishing || a.isDestroyed) return false
        InAppNotice.show(title, msg, InAppNotice.Kind.INFO, ms)
        return true
    }

    private fun toast(ctx: Context?, msg: String) {
        val c = ctx ?: return
        runCatching {
            Toast.makeText(c.applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
