package com.kongbai.modmigrator

import android.content.Context

/**
 * 插件接口。
 *
 * 插件是一个已安装的普通 APK，通过广播暴露能力，宿主用显式 Intent 调用，
 * 不需要宿主在代码里认识插件的任何类（避免互相依赖 classloader）。
 *
 * 插件侧只要声明一个 BroadcastReceiver 响应下方的 action 即可。
 */
object PluginApi {

    const val ACTION_QUERY = "com.kongbai.modmigrator.PLUGIN_QUERY"
    const val ACTION_RUN = "com.kongbai.modmigrator.PLUGIN_RUN"
    const val EXTRA_TOKEN = "token"
    const val EXTRA_ACTION = "action"
    const val EXTRA_ARGS = "args"
    const val EXTRA_LABEL = "label"
    const val EXTRA_DESC = "desc"

    data class Plugin(
        var pkg: String = "",
        var label: String = "",
        var desc: String = "",
        var actions: List<String> = emptyList()
    )

    /** 已安装的插件：向系统查询能响应 PLUGIN_QUERY 的接收器 */
    fun discover(ctx: Context): List<Plugin> {
        val out = ArrayList<Plugin>()
        val i = android.content.Intent(ACTION_QUERY)
        val infos = ctx.packageManager.queryBroadcastReceivers(i, 0)
        for (r in infos) {
            val pkg = r.activityInfo?.packageName ?: continue
            if (pkg == ctx.packageName) continue
            val label = r.loadLabel(ctx.packageManager)?.toString() ?: pkg
            out.add(Plugin(pkg = pkg, label = label))
        }
        return out
    }

    /** 让插件执行一次动作，返回结果字符串；插件没响应返回 null */
    fun run(ctx: Context, pkg: String, action: String, args: String = ""): String? {
        val i = android.content.Intent(ACTION_RUN)
        i.setPackage(pkg)
        i.putExtra(EXTRA_ACTION, action)
        i.putExtra(EXTRA_ARGS, args)
        return try {
            ctx.sendOrderedBroadcast(i, null, object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
                    // 插件通过 setResultData 回传，宿主这里只接收不阻塞
                }
            }, null, android.app.Activity.RESULT_OK, null, null)
            null
        } catch (t: Throwable) {
            null
        }
    }
}
