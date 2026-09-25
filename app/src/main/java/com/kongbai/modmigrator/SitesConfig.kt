package com.kongbai.modmigrator

/**
 * 常用 MC 站点导航。
 *
 * 放在「更多」里，走内置浏览器打开。
 * 地址集中在这里，方便以后增删改。
 */
object SitesConfig {

    data class Site(
        val name: String,
        val url: String,
        val desc: String,
        val group: String
    )

    /** 分组顺序即显示顺序 */
    fun groups(): List<String> = listOf("模组与资源", "红石与投影", "百科与教程", "工具")

    fun all(): List<Site> = listOf(
        // 模组与资源
        Site("Modrinth", "https://modrinth.com", "开源模组平台，本应用的主要数据源", "模组与资源"),
        Site("CurseForge", "https://www.curseforge.com/minecraft", "最大的 MC 模组站", "模组与资源"),
        Site("MCIM 镜像", "https://www.mcimirror.top", "国内镜像，下载更快", "模组与资源"),
        Site("MC百科", "https://www.mcmod.cn", "中文模组百科，查模组用法", "模组与资源"),

        // 红石与投影
        // ⚠️ 原「红石计划 https://www.redstoneplan.com」无法核实（连接失败），
        //    且"红石计划"本身是一个 MOD（ProjectRed 的非官方续作）而非站点，
        //    这里换成确实存在的存档下载入口。
        Site("CurseForge 存档", "https://www.curseforge.com/minecraft/worlds", "地图与生电存档下载", "红石与投影"),
        // ⚠️ 原写的是 litematica.**net**，官方站是 litematica.**org**
        Site("Litematica 官网", "https://litematica.org", "投影模组官网（含用法与 FAQ）", "红石与投影"),
        Site("Minecraft Schematics", "https://www.minecraft-schematics.com", "英文投影站", "红石与投影"),
        Site("Planet Minecraft", "https://www.planetminecraft.com", "地图、皮肤、投影", "红石与投影"),

        // 百科与教程
        Site("中文 Minecraft Wiki", "https://zh.minecraft.wiki", "官方认可的中文百科", "百科与教程"),
        Site("Minecraft Wiki（英文）", "https://minecraft.wiki", "英文原版百科", "百科与教程"),
        // ⚠️ fabricmc.net/wiki 是**旧版 wiki**，Fabric 官网现已指向
        //    独立文档站 docs.fabricmc.net（页面里写 "Visit the docs"）
        Site("Fabric 文档", "https://docs.fabricmc.net", "Fabric 加载器与 API 文档（官方新址）", "百科与教程"),
        Site("Forge 文档", "https://docs.minecraftforge.net", "Forge 开发文档", "百科与教程"),

        // 工具
        Site("Modrinth 状态", "https://status.modrinth.com", "数据源可用性", "工具"),
        Site("MC 版本速查", "https://minecraft.wiki/w/Java_Edition_version_history", "版本历史", "工具")
    )

    fun byGroup(g: String): List<Site> = all().filter { it.group == g }
}
