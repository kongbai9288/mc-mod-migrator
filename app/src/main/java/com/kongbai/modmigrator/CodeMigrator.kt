package com.kongbai.modmigrator

import java.io.File

/**
 * 代码级迁移（面向开发者）。
 *
 * 场景：你自己写模组，要把工程从 Forge 迁到 Fabric（或反过来、
 * 或 NeoForge），需要改 build.gradle、改包名、改 API 调用。
 * 这些替换是**机械且有固定对应表**的，可以自动做。
 *
 * 只处理最常见的映射，且**先扫描、出报告、让你勾选后再改**，
 * 绝不直接改写你的源码——改错了会很难查。
 */
object CodeMigrator {

    /** 一次替换规则 */
    data class Rule(
        val from: String,
        val to: String,
        val desc: String,
        val file: String = "*.java"   // 作用范围
    )

    /** 扫描出来的待改项 */
    data class Hit(
        val file: String,
        val line: Int,
        val from: String,
        val to: String,
        val desc: String,
        val snippet: String
    )

    // ---------- Forge → Fabric ----------
    private val FORGE_TO_FABRIC = listOf(
        Rule("net.minecraftforge", "net.fabricmc", "包名更换", "*.java"),
        Rule("@Mod(", "@Mod(", "模组主类注解（Fabric 用 ModInitializer）", "*.java"),
        Rule("FMLJavaModLoadingContext", "FabricLoader", "加载上下文", "*.java"),
        Rule("Mod.EventBusSubscriber", "FabricLoader", "事件注册", "*.java"),
        Rule("IEventBus", "FabricLoader", "事件总线", "*.java"),
        Rule("RegistryObject", "Identifier", "注册表对象", "*.java"),
        Rule("DeferredRegister", "Registry.register", "延迟注册", "*.java"),
        Rule("DistExecutor", "FabricLoader.getInstance().getEnvironmentType()", "端判定", "*.java"),
        Rule("forgeGradle", "fabric-loom", "构建插件", "build.gradle"),
        Rule("minecraftForge", "fabricApi", "依赖配置", "build.gradle")
    )

    // ---------- Fabric → Forge ----------
    private val FABRIC_TO_FORGE = listOf(
        Rule("net.fabricmc", "net.minecraftforge", "包名更换", "*.java"),
        Rule("ModInitializer", "@Mod 主类", "入口类", "*.java"),
        Rule("FabricLoader", "FMLJavaModLoadingContext", "加载上下文", "*.java"),
        Rule("Registry.register", "DeferredRegister", "注册方式", "*.java"),
        Rule("fabric-loom", "forgeGradle", "构建插件", "build.gradle"),
        Rule("fabricApi", "minecraftForge", "依赖配置", "build.gradle")
    )

    // ---------- Forge → NeoForge ----------
    private val FORGE_TO_NEO = listOf(
        Rule("net.minecraftforge", "net.neoforged", "包名更换", "*.java"),
        Rule("net.minecraftforge.event", "net.neoforged.bus.api", "事件包名", "*.java"),
        Rule("forgeGradle", "neoForge", "构建插件", "build.gradle"),
        Rule("minecraftForge", "neoForge", "依赖配置", "build.gradle")
    )

    fun rulesFor(from: String, to: String): List<Rule> {
        val a = from.lowercase()
        val b = to.lowercase()
        return when {
            a == "forge" && b == "fabric" -> FORGE_TO_FABRIC
            a == "fabric" && b == "forge" -> FABRIC_TO_FORGE
            a == "forge" && b == "neoforge" -> FORGE_TO_NEO
            else -> emptyList()
        }
    }

    fun supportedPairs(): List<Pair<String, String>> = listOf(
        "forge" to "fabric",
        "fabric" to "forge",
        "forge" to "neoforge"
    )

    /**
     * 扫描工程目录，找出所有可自动替换的点。
     * 只读不改，返回命中列表。
     */
    fun scan(root: File, from: String, to: String): List<Hit> {
        val rules = rulesFor(from, to)
        if (rules.isEmpty()) return emptyList()

        val out = ArrayList<Hit>()
        val exts = setOf(".java", ".kt", ".gradle", ".kts", ".json")
        val files = try {
            root.walkTopDown()
                .filter { it.isFile && it.extension.let { e -> ".$e" in exts } }
                .filter { !it.path.contains("/.git/") && !it.path.contains("/build/") }
                .take(600)
                .toList()
        } catch (t: Throwable) {
            emptyList()
        }

        for (f in files) {
            val lines = try {
                f.readLines()
            } catch (t: Throwable) {
                continue
            }
            val scope = scopeOf(f.name)
            for ((i, line) in lines.withIndex()) {
                for (r in rules) {
                    if (r.file != scope && r.file != "*") continue
                    if (!line.contains(r.from)) continue
                    // 跳过注释行，避免把说明文字也改掉
                    val trimmed = line.trimStart()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue
                    out.add(
                        Hit(
                            file = f.name,
                            line = i + 1,
                            from = r.from,
                            to = r.to,
                            desc = r.desc,
                            snippet = line.trim().take(90)
                        )
                    )
                }
            }
        }
        return out
    }

    private fun scopeOf(name: String): String =
        when {
            name == "build.gradle" || name == "build.gradle.kts" -> "build.gradle"
            name.endsWith(".java") -> "*.java"
            else -> "*"
        }

    /**
     * 执行替换。
     * **会先备份原文件为 .bak**，出问题能回滚。
     * @param hits 用户勾选的待改项
     */
    fun apply(root: File, hits: List<Hit>): Pair<Int, Int> {
        var changed = 0
        var failed = 0
        val byFile = hits.groupBy { it.file }

        for ((name, list) in byFile) {
            val f = File(root, name)
            if (!f.exists()) {
                failed++
                continue
            }
            try {
                // 备份
                val bak = File(root, "$name.bak")
                if (!bak.exists()) f.copyTo(bak, overwrite = true)

                val lines = f.readLines().toMutableList()
                for (h in list) {
                    val idx = h.line - 1
                    if (idx < 0 || idx >= lines.size) continue
                    lines[idx] = lines[idx].replace(h.from, h.to)
                }
                f.writeText(lines.joinToString("\n"))
                changed++
            } catch (t: Throwable) {
                failed++
            }
        }
        return changed to failed
    }

    /** 把扫描结果拼成报告 */
    fun report(hits: List<Hit>): String {
        if (hits.isEmpty()) {
            return "没有发现需要改动的地方。\n\n" +
                "可能是这个方向的迁移规则还没收录，" +
                "或者工程里已经改过了。"
        }
        val sb = StringBuilder()
        sb.append("发现 ${hits.size} 处可自动替换：\n\n")
        val byDesc = hits.groupBy { it.desc }
        for ((desc, list) in byDesc) {
            sb.append("【").append(desc).append("】").append(list.size).append(" 处\n")
            list.take(4).forEach {
                sb.append("  ${it.file}:${it.line}  ${it.from} → ${it.to}\n")
            }
            if (list.size > 4) sb.append("  …等 ${list.size} 处\n")
            sb.append('\n')
        }
        sb.append("替换前会自动备份为 .bak，可随时回滚。")
        return sb.toString()
    }
}
