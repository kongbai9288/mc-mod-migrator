package com.kongbai.modmigrator

import android.content.Context

/**
 * 断网闸门。
 *
 * 之前只有一个全局 `K.OFFLINE` 开关：一开就是把**所有**联网功能全断掉
 * —— 商店、翻译、资讯、更新检查、公告，一个不剩。
 *
 * 问题是这些功能对网络的依赖程度差很远：
 *   - 商店搜索没了网络等于不能用，但**下载**是用户主动要的
 *   - 翻译断网只影响质量（还有本地词典兜底），不影响能不能用
 *   - 资讯/公告断网只是看不到新内容
 *   - 更新检查断网只是不知道有新版本
 *
 * 用户只想省流量或避免某个模块联网时，却被迫把所有功能一起关掉。
 *
 * 现在分两层：
 *   1. `K.OFFLINE` 仍是总开关（打开 = 全部断网，保持原有行为）
 *   2. 总开关关闭时，按分项开关判断
 */
object NetGate {

    /** 允许联网的领域 */
    enum class Area { SEARCH, TRANSLATE, FEED, UPDATE, ANNOUNCE }

    /** 该领域现在是否允许联网 */
    fun allow(ctx: Context?, area: Area): Boolean {
        if (ctx == null) return true
        val p = Prefs.get(ctx)
        // 总开关优先：打开就全部断网
        if (p.getBoolean(K.OFFLINE, false)) return false
        val key = when (area) {
            Area.SEARCH -> K.OFFLINE_SEARCH
            Area.TRANSLATE -> K.OFFLINE_TRANSLATE
            Area.FEED -> K.OFFLINE_FEED
            Area.UPDATE -> K.OFFLINE_UPDATE
            Area.ANNOUNCE -> K.OFFLINE_ANNOUNCE
        }
        // 分项默认 false = 默认允许联网，只有用户勾了才断
        return !p.getBoolean(key, false)
    }

    /** 断网时给用户的说明（用于 toast / 空态文案） */
    fun why(ctx: Context?, area: Area): String {
        if (ctx == null) return ""
        val global = Prefs.get(ctx).getBoolean(K.OFFLINE, false)
        return if (global) "已开启离线模式，此项需要联网"
        else "已在设置里单独关闭此项的联网"
    }
}
