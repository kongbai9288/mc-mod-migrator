package com.kongbai.modmigrator

/**
 * 商店页的搜索结果**跨 Fragment 重建**保存。
 *
 * ⚠️ 真实问题：搜索出一屏结果，切到别的底部页签再切回来，
 * 列表空了，只剩一句"搜索结果（0）"。
 *
 * 根因：底部导航用的是 `replace()` 而不是 show/hide
 * —— 每次切页签 Fragment 都被**销毁重建**，
 * 而结果列表是 Fragment 的实例字段，重建即清零。
 * 搜索要重新联网、翻页偏移也回到 0，等于白搜一遍。
 *
 * 放在单例里：Fragment 销毁不影响它，切回来直接取回上一次的结果。
 * 用 `stamp` 记录查询条件，条件变了就重新搜，不会拿旧结果糊弄。
 */
object MarketState {

    /** 搜索/推荐的结果 */
    val searchResults = mutableListOf<MarketMod>()

    /** 收藏夹列表（每次显示时重新读，这里只留一份缓存避免闪空） */
    val favResults = mutableListOf<MarketMod>()

    /** 当前页签：search / fav */
    var tab: String = "search"

    // ---- 翻页状态 ----
    var offset: Int = 0
    var hasMore: Boolean = false
    var query: String = ""
    var mc: String = ""
    var loader: String = ""

    /** 当前这组结果对应的查询条件；变了就说明是旧结果，不能再用 */
    fun stamp(): String = "$query|$mc|$loader"

    /** 记下这次查询的条件并重置翻页 */
    fun begin(q: String, mcVer: String, ld: String) {
        query = q
        mc = mcVer
        loader = ld
        offset = 0
        hasMore = false
    }

    /** 彻底清空（用户主动换关键词时） */
    fun reset() {
        searchResults.clear()
        offset = 0
        hasMore = false
    }

    /**
     * 列表是否已经空了很久不用。
     * 用于判断"切回来要不要自动重新推荐一次"。
     */
    fun hasContent(): Boolean = searchResults.isNotEmpty() || favResults.isNotEmpty()
}
