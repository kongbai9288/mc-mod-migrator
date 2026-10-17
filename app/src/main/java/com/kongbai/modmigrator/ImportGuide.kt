package com.kongbai.modmigrator

import android.content.Context
import android.content.Intent

/**
 * 整合包导入引导 —— 私有目录读不出来（或压根读不到）时的最后一条路。
 *
 * 为什么必须有这条路：Android 11 起第三方应用写不了别的应用的
 * `Android/data`，本应用再怎么提权也只能是"读"；而**启动器写自己的
 * 私有目录不受这条限制**。所以把内容打成 .mrpack 放到公共目录，
 * 再让启动器自己导进去，是 Android 14/16 上唯一稳的做法。
 *
 * 步骤写在哪：放在 `strings.xml` 的 string-array 里，不在 Kotlin 里硬编码，
 * 这样英文包能一起翻。Kotlin 只负责"该用哪一套步骤"和"能不能打开它"。
 *
 * ⚠️ 步骤的可靠程度不一样：
 *   - Pojav 那套是**用户提供的实际流程**，标 [verified]=true。
 *   - 其余启动器我没核到菜单路径，统一走通用步骤并标 [verified]=false，
 *     界面上会明说"未核实"，不拿猜的菜单名冒充。
 */
object ImportGuide {

    data class Guide(
        /** 显示名 */
        val launcher: String,
        /** 包名，用于「打开」按钮；空串 = 不知道是哪个包 */
        val pkg: String,
        /** 步骤，已按当前语言取好 */
        val steps: List<String>,
        /** 这套步骤有没有核实过 */
        val verified: Boolean
    )

    /**
     * 按启动器选步骤。
     *
     * 只认有确凿依据的 key；认不出就退回通用步骤。
     * 宁可给通用步骤 + 标未核实，也不编一套菜单位置。
     */
    fun forEntry(ctx: Context, e: LauncherDirs.Entry): Guide {
        val pkg = e.pkgs.firstOrNull().orEmpty()
        return when (e.key) {
            "pojav" -> Guide(
                e.name, pkg,
                ctx.resources.getStringArray(R.array.import_steps_pojav).toList(),
                verified = true
            )
            else -> generic(ctx, e.name, pkg)
        }
    }

    /** 清单里没有、但用户从已装列表里挑了一个包 */
    fun forPackage(ctx: Context, pkg: String, label: String): Guide {
        val key = LauncherBrand.fromPath(pkg).key
        return if (key == "pojav") {
            Guide(
                label, pkg,
                ctx.resources.getStringArray(R.array.import_steps_pojav).toList(),
                verified = true
            )
        } else {
            generic(ctx, label, pkg)
        }
    }

    fun generic(ctx: Context, label: String, pkg: String = ""): Guide = Guide(
        label.ifBlank { ctx.getString(R.string.dir_unknown_title) },
        pkg,
        ctx.resources.getStringArray(R.array.import_steps_generic).toList(),
        verified = false
    )

    /** 装了没有。查询不到也算没装，免得点了「打开」没反应。 */
    fun installed(ctx: Context, pkg: String): Boolean {
        if (pkg.isBlank()) return false
        return try {
            ctx.packageManager.getLaunchIntentForPackage(pkg) != null
        } catch (t: Throwable) {
            Err.ignore(t, "ImportGuide.installed")
            false
        }
    }

    /** 拉起启动器。返回 false = 拉不起来（没装或没有可启动的 Activity）。 */
    fun open(ctx: Context, pkg: String): Boolean {
        if (pkg.isBlank()) return false
        return try {
            val it = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(it)
            true
        } catch (t: Throwable) {
            // 目标没装、被停用、或 ROM 拦了跨应用启动，都可能走到这里
            Err.ignore(t, "ImportGuide.open")
            false
        }
    }
}
