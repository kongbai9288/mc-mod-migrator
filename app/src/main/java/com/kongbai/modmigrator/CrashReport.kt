package com.kongbai.modmigrator

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import java.io.File

/**
 * 崩溃自报：上次崩溃的堆栈写进 crash.log，
 * 下次打开时弹出来（带复制按钮），方便定位问题。
 */
object CrashReport {

    fun file(ctx: Context): File = File(ctx.filesDir, "crash.log")

    fun read(ctx: Context): String {
        return try {
            val f = file(ctx)
            if (!f.exists()) "" else f.readText().trim()
        } catch (t: Throwable) {
            ""
        }
    }

    fun clear(ctx: Context) {
        try {
            file(ctx).delete()
        } catch (t: Throwable) { Err.ignore(t, "file(ctx).delete()") }
    }

    /** 有崩溃记录就弹窗；只在 Activity 里调，且整体包了 try 防止二次崩溃 */
    fun showIfAny(activity: Activity) {
        try {
            val txt = read(activity)
            if (txt.isBlank()) return
            val short = if (txt.length > 2500) txt.takeLast(2500) else txt
            // 用 MaterialAlertDialogBuilder：全项目其他地方都用它，
            // 系统 AlertDialog 不认 Material 的 materialAlertDialogTheme，
            // 按钮与标题不跟主题色，看起来像另一个应用弹出来的。
            com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle("上次崩溃了")
                .setMessage(
                    "这次启动前发生了一次崩溃，堆栈如下。\n" +
                        "你可以复制后发给开发者，或点清空继续用：\n\n$short"
                )
                .setPositiveButton("复制并清空") { _, _ ->
                    val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("crash", txt))
                    clear(activity)
                }
                .setNegativeButton("清空") { _, _ -> clear(activity) }
                .setNeutralButton("暂时忽略", null)
                .show()
        } catch (t: Throwable) {
            // 弹窗本身出错也不能把应用搞崩
                 Err.ignore(t, "弹窗本身出错也不能把应用搞崩")
             }
    }
}
