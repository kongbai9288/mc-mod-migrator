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
    /** 插件回传动作列表时的分隔符 */
    const val ACT_SEP = "|"

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
        val infos = try {
            ctx.packageManager.queryBroadcastReceivers(i, 0)
        } catch (t: Throwable) {
            Err.ignore(t, "查询插件接收器")
            emptyList()
        }
        for (r in infos) {
            val pkg = r.activityInfo?.packageName ?: continue
            if (pkg == ctx.packageName) continue
            val label = r.loadLabel(ctx.packageManager)?.toString() ?: pkg
            // ── 动作列表要向插件**问**，不能宿主这边猜 ──────────
            // 之前 Plugin.actions 永远是 emptyList()，
            // 界面上只能列出写死的 "ping/scan_instance/export/import"，
            // 那就是宿主单方面假设的名字，真实插件未必支持任何一个 ——
            // 点了永远只是"已发送"，什么也不会发生。
            // 这里发一次显式 QUERY 广播，让插件把真实动作回传。
            val acts = queryActions(ctx, pkg)
            out.add(Plugin(pkg = pkg, label = label, actions = acts))
        }
        return out
    }

    /**
     * 向插件发一次显式 QUERY 广播，取它支持的动作列表。
     *
     * 必须 `setPackage` 转成显式 Intent：Android 8.0（API 26）起
     * **隐式广播不再送达静态注册的接收器**，而插件正是静态注册在
     * Manifest 里的接收器 —— 不指定包名，插件根本收不到。
     */
    private fun queryActions(ctx: Context, pkg: String): List<String> {
        val i = android.content.Intent(ACTION_QUERY).setPackage(pkg)
        val res = sendAndWait(ctx, i)
        if (res.isNullOrBlank()) return emptyList()
        return res.split(ACT_SEP).map { it.trim() }.filter { it.isNotBlank() }
    }

    /**
     * 发有序广播并等待结果。
     *
     * 之前的实现**永远返回 null**：resultReceiver 的 onReceive 是空的，
     * 而且 `sendOrderedBroadcast` 之后立刻 return null，
     * 插件回传的结果被直接丢弃 —— 界面上只能永远显示
     * "已发送（异步回传）"，用户以为执行了，其实什么都没发生、也没结果。
     *
     * 这里用 CountDownLatch 等待 resultReceiver 回调，
     * 拿到 `resultData` 再返回；超时或异常返回 null，
     * 最多等 3 秒（BroadcastReceiver 本身有 10 秒 ANR 限制）。
     *
     * ⚠️ 不能在**主线程**调用：会阻塞等结果。
     */
    private fun sendAndWait(ctx: Context, i: android.content.Intent, timeoutMs: Long = 3000L): String? {
        return try {
            val latch = java.util.concurrent.CountDownLatch(1)
            val box = android.util.SparseArray<String>(1)
            val r = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
                    try {
                        val d = resultData
                        if (!d.isNullOrBlank()) box.put(0, d)
                    } catch (t: Throwable) {
                        Err.ignore(t, "读取插件回传结果")
                    } finally {
                        latch.countDown()
                    }
                }
            }
            ctx.sendOrderedBroadcast(
                i, null, r, null,
                android.app.Activity.RESULT_OK, null, null
            )
            latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            box.get(0)
        } catch (t: Throwable) {
            Err.ignore(t, "发送插件广播")
            null
        }
    }

    /**
     * 让插件执行一次动作，返回结果字符串；插件没响应返回 null。
     *
     * ⚠️ **必须在后台线程调用**（内部会等结果）。
     */
    fun run(ctx: Context, pkg: String, action: String, args: String = ""): String? {
        if (pkg.isBlank()) return null
        val i = android.content.Intent(ACTION_RUN).setPackage(pkg)
        i.putExtra(EXTRA_ACTION, action)
        i.putExtra(EXTRA_ARGS, args)
        // 给足时间：插件可能要扫实例或导出文件
        return sendAndWait(ctx, i, 8000L)
    }
}
