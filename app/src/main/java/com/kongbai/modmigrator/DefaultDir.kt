package com.kongbai.modmigrator

import android.content.Context
import java.io.File

/**
 * 默认工作目录：App 私有外部存储。
 *
 * 为什么需要它：
 *   之前一上来就要用户授权一个目录（SAF），不授权就几乎什么都干不了。
 *   但 Android 上 App 的**私有外部目录**（getExternalFilesDir）
 *   是**不需要任何存储权限**的——系统本来就是给我们用的。
 *
 * 所以现在的策略：
 *   - 没授权任何目录 → 直接用 App 私有目录，开箱可用
 *   - 用户在设置里授权了自己的目录 → 用用户那个（可以跨应用访问，
 *     比如直接落到 FCL 的 mods 里）
 *
 * 卸载 App 时这个目录会被系统一起清掉，所以重要东西建议还是授权外部目录。
 */
object DefaultDir {

    /** 根目录：/sdcard/Android/data/<包名>/files/ModMigrator */
    fun root(ctx: Context): File {
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val d = File(base, "ModMigrator")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun sub(ctx: Context, name: String): File {
        val d = File(root(ctx), name)
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun mods(ctx: Context): File = sub(ctx, "mods")
    fun configs(ctx: Context): File = sub(ctx, "configs")
    fun packs(ctx: Context): File = sub(ctx, "packs")
    fun trash(ctx: Context): File = sub(ctx, "trash")
    fun icons(ctx: Context): File = sub(ctx, "icons")
    fun lang(ctx: Context): File = sub(ctx, "lang")
    fun logs(ctx: Context): File = sub(ctx, "logs")
    fun cache(ctx: Context): File = sub(ctx, "cache")
    fun downloads(ctx: Context): File = sub(ctx, "downloads")

    /** 可读路径，给用户看 */
    fun path(ctx: Context): String = root(ctx).absolutePath

    /** 总占用字节 */
    fun usage(ctx: Context): Long {
        return try {
            root(ctx).walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        } catch (t: Throwable) {
            0L
        }
    }

    /** 清空所有内容（设置里的「清理全部」用） */
    fun clear(ctx: Context): Int {
        var n = 0
        try {
            root(ctx).walkTopDown().forEach {
                if (it.isFile && it.delete()) n++
            }
        } catch (t: Throwable) {
        }
        return n
    }
}
