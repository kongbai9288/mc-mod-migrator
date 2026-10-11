package com.kongbai.modmigrator

import android.content.Context
import androidx.documentfile.provider.DocumentFile

/**
 * 目录解析的唯一入口。
 *
 * 之前每个功能自己写一行 `Targets.modsDir(ctx) ?: WorkDir.modsDir(ctx)`，
 * 结果同一个问题写出两种相反的顺序（工具箱里就有 `WorkDir ?: Targets`），
 * 于是同一个操作在不同按钮下落到不同目录 —— 用户看到的是
 * 「明明装过了，这个按钮却说没有」。
 *
 * 顺序只在这里定一次：游戏目录优先，工作目录兜底。
 * 以后要加别的候选（比如最近用过的目录），也只改这一处。
 */
object DirResolve {

    /** mods 目录：先找游戏目录里的，找不到再用工作目录里的 */
    fun modsDir(ctx: Context): DocumentFile? =
        Targets.modsDir(ctx) ?: WorkDir.modsDir(ctx)
}
