package com.kongbai.modmigrator

/**
 * 统一记录被吞掉的异常。
 *
 * ## 为什么要有这个
 *
 * 这是 AI 生成代码最典型的缺陷：**异常黑洞**。
 * 到处写 `catch (t: Throwable) { }`，语法完全正确、能编译、能跑通主流程，
 * 但一旦出错——没有日志、没有提示、没有上报，用户只看到"没反应"，
 * 而排查的人连发生了什么都不知道。
 *
 * 全项目曾有 87 处空 catch，这是"为什么改不动、为什么总是猜"的直接原因：
 * 失败被静默吞掉，等于没有任何现场信息。
 *
 * ## 用法
 *
 * ```kotlin
 * try { risky() } catch (t: Throwable) { Err.ignore(t, "读取配置") }
 * ```
 *
 * 这样即使不影响流程，也会进日志缓冲，用户分享崩溃日志时能看到
 * "崩之前到底发生了什么"。
 */
object Err {

    /** 静默但留痕：不影响流程，但记录到日志 */
    fun ignore(t: Throwable, what: String = "") {
        val msg = if (what.isBlank()) "已忽略异常" else "已忽略异常：$what"
        try {
            LogCenter.w("Err", "$msg -> ${t.javaClass.simpleName}: ${t.message?.take(120) ?: ""}")
        } catch (_: Throwable) {
        }
        try {
            timber.log.Timber.w(t, msg)
        } catch (_: Throwable) {
        }
    }

    /** 值得注意的失败：记 warn */
    fun warn(t: Throwable, what: String) {
        try {
            LogCenter.w("Err", "$what -> ${t.javaClass.simpleName}: ${t.message?.take(120) ?: ""}")
        } catch (_: Throwable) {
        }
        try {
            timber.log.Timber.w(t, what)
        } catch (_: Throwable) {
        }
    }

    /** 功能性失败：记 error，界面可据此提示用户 */
    fun fail(t: Throwable, what: String) {
        try {
            LogCenter.e("Err", "$what -> ${t.javaClass.simpleName}: ${t.message?.take(200) ?: ""}")
        } catch (_: Throwable) {
        }
        try {
            timber.log.Timber.e(t, what)
        } catch (_: Throwable) {
        }
    }

    /**
     * 包一层，吞掉异常但留痕。
     * 用于"失败也无所谓"的清理/兜底场景，替代裸 try-catch。
     */
    inline fun swallow(what: String = "", block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            ignore(t, what)
        }
    }
}
