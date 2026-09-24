package com.kongbai.modmigrator

import androidx.documentfile.provider.DocumentFile
import java.util.Locale

/**
 * 模组启用 / 禁用（**不移动文件**）。
 *
 * 之前要禁用一个模组得把它挪出 mods 目录——挪走之后模组丢了上下文，
 * 恢复时容易放错地方，而且在 SAF 授权的目录里反复移动很慢。
 *
 * 现在用加载器**原生支持**的做法：改扩展名。
 *   - Fabric / Forge / Quilt 都只加载 .jar 结尾的文件
 *   - 禁用：xxx.jar → xxx.jar.disabled
 *   - 启用：xxx.jar.disabled → xxx.jar
 *
 * 优点：文件始终在同目录、只是一次 rename（秒完成）、
 * 加载器天然识别、随时可反悔。
 */
object ModToggle {

    const val SUFFIX = ".jar.disabled"

    /** 这个文件当前是不是被禁用（只看扩展名，不读内容） */
    fun isDisabled(f: DocumentFile): Boolean {
        val n = (f.name ?: "").lowercase(Locale.ROOT)
        return n.endsWith(SUFFIX)
    }

    /** 显示名：去掉 .disabled 后缀，列表里好看 */
    fun displayName(f: DocumentFile): String {
        val n = f.name ?: return ""
        return if (n.lowercase(Locale.ROOT).endsWith(SUFFIX)) {
            n.substring(0, n.length - SUFFIX.length)
        } else n
    }

    /** 禁用：jar → jar.disabled。返回重命名后的文件（失败返回 null） */
    fun disable(f: DocumentFile): DocumentFile? {
        val n = f.name ?: return null
        if (isDisabled(f)) return f
        return try {
            if (f.renameTo(n + SUFFIX)) f else null
        } catch (t: Throwable) {
            null
        }
    }

    /** 启用：jar.disabled → jar */
    fun enable(f: DocumentFile): DocumentFile? {
        val n = f.name ?: return null
        if (!isDisabled(f)) return f
        return try {
            val back = n.substring(0, n.length - SUFFIX.length)
            if (f.renameTo(back)) f else null
        } catch (t: Throwable) {
            null
        }
    }

    /** 切换状态，返回切换后是否被禁用；失败返回 null */
    fun toggle(f: DocumentFile): Boolean? {
        return if (isDisabled(f)) {
            if (enable(f) != null) false else null
        } else {
            if (disable(f) != null) true else null
        }
    }
}
