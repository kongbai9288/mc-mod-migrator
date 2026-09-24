package com.kongbai.modmigrator

import java.util.Locale

/**
 * 中文 / 简称 → 英文搜索词。
 *
 * 解决的问题：模组市场（Modrinth、CurseForge）只认英文关键词。
 * 国内用户搜「小地图」「连锁挖矿」「优化」是搜不到东西的，
 * 而直接用中文去打英文 API 只会返回空结果。
 *
 * 做法：搜之前先把中文/简称映射成英文词，再发出去。
 * 词条按「玩家实际会怎么搜」来收，而不是按模组的官方分类。
 *
 * 这个词表是纯数据，不涉及任何第三方代码。
 */
object ModAliases {

    /** 中文关键词（或简称）→ 英文搜索词 */
    private val MAP: Map<String, String> by lazy { buildMap() }

    private fun buildMap(): Map<String, String> {
        val m = HashMap<String, String>()
        fun put(en: String, vararg zh: String) {
            for (z in zh) m[z.lowercase(Locale.ROOT)] = en
        }

        // 性能优化类
        put("sodium", "钠", "sodium", "优化")
        put("lithium", "锂", "lithium")
        put("phosphor", "磷", "phosphor")
        put("starlight", "星光", "starlight")
        put("ferrite core", "铁氧体", "ferrite")
        put("entity culling", "实体剔除", "剔除")
        put("clumps", "经验球合并", "经验合并")
        put("dynamic fps", "动态帧率", "动态fps")
        put("smooth boot", "平滑启动", "快速启动")
        put("cull leaves", "树叶剔除", "树叶优化")
        put("more culling", "更多剔除")
        put("radium", "镭", "radium")
        put("optifine", "optifine", "高清修复", "光影")
        put("iris shaders", "iris", "彩虹", "着色器", "光影mod")
        put("fabric api", "fabric api", "fabricapi", "织物api")

        // 功能辅助
        put("jei", "jei", "物品管理器", "查看物品", "合成表", "jei物品")
        put("rei", "rei", "rei物品")
        put("emi", "emi", "emi物品")
        put("mod menu", "mod menu", "模组菜单", "模组管理界面")
        put("journeymap", "journeymap", "旅行地图", "小地图", "地图mod")
        put("xaero minimap", "xaero", "xaero小地图", "小地图xaero", "xaeros")
        put("minimap", "小地图", "minimap", "迷你地图")
        put("vein miner", "连锁挖矿", "连锁", "脉矿", "veinminer", "连锁采集")
        put("ftb chunks", "ftb区块", "ftb chunks", "区块加载", "领地")
        put("waystones", "传送石", "路标", "waystones", "传送点")
        put("gravestone", "墓碑", "死亡不掉落", "gravestone")
        put("corpse", "尸体", "corpse")
        put("iron chests", "铁箱子", "更多箱子", "iron chests", "大箱子")
        put("sophisticated backpacks", "背包", "高级背包", "sophisticated", "旅行背包")
        put("inventory tweaks", "背包整理", "一键整理", "整理背包", "inventory")
        put("mouse tweaks", "鼠标手势", "mouse tweaks", "快速整理")
        put("controlling", "按键", "控制键", "controlling", "按键设置")
        put("appleskin", "苹果皮", "饥饿显示", "食物", "appleskin")
        put("jade", "jade", "信息显示", "方块信息", "hwyla", "wthit", "提示框")
        put("light overlay", "亮度显示", "刷怪", "光照", "lightoverlay", "防刷怪")
        put("mini hud", "小型信息", "minihud", "调试信息")
        put("tweakeroo", "tweakeroo", "辅助", "作弊")
        put("item zoom", "放大", "itemzoom", "物品放大")
        put("shulker box tooltip", "潜影盒", "shulker", "盒子预览")
        put("replay mod", "回放", "录像", "replaymod", "录像mod")
        put("world edit", "创世神", "worldedit", "we", "建筑")
        put("litematica", "投影", "litematica", " schematic", "建筑投影")
        put("world preview", "预览", "worldpreview")

        // 存储与物流（生电）
        put("applied energistics 2", "ae2", "应用能源", "ae", "能源网络", "物流存储")
        put("refined storage", "rs", "精炼存储", "refinedstorage", "存储网络")
        put("create", "机械动力", "create", "Create", "传动", "机械")
        put("ender storage", "末影箱", "enderstorage", "末影存储")
        put("functional storage", "功能存储", "functionalstorage", "抽屉")
        put("storage drawers", "抽屉", "storagedrawers", "存储抽屉")
        put("sophisticated storage", "高级存储", "sophisticatedstorage")
        put("pipez", "管道", "pipez", "管道传输")
        put("laser bridge", "激光", "laserbridge")

        // 红石与生电
        put("carpet", "地毯", "carpet", "carpetmod", "原版增强", "生电")
        put("carpet extra", "地毯附加", "carpetextra")
        put("redstone", "红石", "redstone")
        put("litematica printer", "打印机", "投影打印")
        put("tick", "刻", "tick")
        put("smooth chunk", "平滑区块")
        put("servux", "servux")

        // 冒险与内容
        put("twilight forest", "暮色森林", "twilightforest", "暮色")
        put("the aether", "天堂", "aether", "以太")
        put("botania", "植物魔法", "botania", "花药", "魔法")
        put("blood magic", "血魔法", "bloodmagic")
        put("thermal", "热力", "thermal", "热力膨胀")
        put("alex mobs", "生物", "alexmobs", "更多生物", "新生物")
        put("mowzie mobs", "mowzie", "巨兽")
        put("yungs", "建筑", "yungs", "地牢", "更好的")
        put(" farmers delight", "农夫乐事", "farmersdelight", "农耕", "种田", "美食")
        put("alex caves", "洞穴", "alexcaves", "更多洞穴")
        put("deeper darker", "深暗", "deepdark", "更深更暗")
        put("biomes o plenty", "更多生物群系", "biomesoplenty", "群系")
        put("oh the biomes", "更多群系", "biomes")

        // 服务器与工具
        put("essential", "essential")
        put("voice chat", "语音", "voicechat", "语音聊天", "简单语音")
        put("skin", "皮肤", "skin")
        put("custom skin loader", "皮肤加载", "customskinloader", "自定义皮肤")
        put("3d skin layers", "3d皮肤", "皮肤层", "skinlayers")
        put("first person model", "第一人称", "第一视角", "firstperson")
        put("not enough animations", "动画", "notenoughanimations")

        return m
    }

    /**
     * 把用户输入翻译成英文搜索词。
     * 命中词表就用英文词；没命中就原样返回（英文关键词直接透传）。
     */
    fun translate(q: String): String {
        val key = q.trim().lowercase(Locale.ROOT)
        if (key.isBlank()) return q
        return MAP[key] ?: q
    }

    /** 是否命中了别名表（界面可以提示"已按 xxx 搜索"） */
    fun hit(q: String): Boolean {
        val key = q.trim().lowercase(Locale.ROOT)
        return MAP.containsKey(key)
    }

    /** 给用户看的提示：搜「小地图」实际按什么搜 */
    fun hint(q: String): String {
        val t = translate(q)
        return if (t != q.trim()) "（按「$t」搜索）" else ""
    }

    /** 常见中文标签，搜索框下方做快捷入口 */
    fun hotWords(): List<String> = listOf(
        "小地图", "连锁挖矿", "优化", "投影", "红石", "背包",
        "暮色森林", "机械动力", "应用能源", "语音", "皮肤", "合成表"
    )
}
