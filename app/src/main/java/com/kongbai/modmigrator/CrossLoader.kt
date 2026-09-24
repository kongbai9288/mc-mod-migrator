package com.kongbai.modmigrator

import java.util.Locale

/**
 * 跨加载器迁移（Forge ↔ Fabric ↔ Quilt ↔ NeoForge）。
 *
 * **核心约束（必须遵守，否则会做出错事）**：
 *
 *  - **允许**：同一个 MC 版本下，换加载器。
 *    例如 1.20.1 的 Forge 模组 → 找它的 Fabric 版（同为 1.20.1）。
 *
 *  - **禁止**：跨 MC 版本迁移。
 *    1.18.2 的模组直接拿到 1.20.1 用，绝大部分会崩——
 *    MC 主版本之间改了大量内部 API，模组二进制不兼容。
 *    这种需求应该走"找新版"，而不是"迁移旧文件"。
 *
 * 所以这里的流程是：
 *   1. 先确认目标 MC 版本 == 源 MC 版本，不等就**直接拒绝**并说明原因
 *   2. 用模组名（或 Mod ID）去 Modrinth 搜同名项目
 *   3. 过滤出「支持目标加载器 + 目标 MC 版本」的文件
 *   4. 只取**该 MC 版本下**的最新文件，绝不给其它版本的
 *
 * 不做的事：不尝试"转换"模组本体。模组没有通用转换器，
 * 声称能转换的工具实际是帮你去找对应的另一个版本，这里如实照做。
 */
object CrossLoader {

    /** 加载器别名归一（Modrinth 用 fabric/forge/quilt/neoforge） */
    fun norm(loader: String): String = when (loader.lowercase(Locale.ROOT)) {
        "fabric", "fabricloader", "fabric-loader" -> "fabric"
        "forge", "minecraftforge" -> "forge"
        "neoforge", "neo" -> "neoforge"
        "quilt", "quiltloader" -> "quilt"
        else -> loader.lowercase(Locale.ROOT)
    }

    /** 从一个文件名里猜出 MC 版本，比如 xxx-1.20.1.jar → 1.20.1 */
    fun guessMc(fileName: String): String {
        val r = Regex("(?<!\\d)(1\\.\\d{1,2}(?:\\.\\d{1,2})?)(?!\\d)")
        return r.find(fileName)?.groupValues?.get(1) ?: ""
    }

    /** 从一个文件名里猜出加载器 */
    fun guessLoader(fileName: String): String {
        val low = fileName.lowercase(Locale.ROOT)
        return when {
            low.contains("neoforge") -> "neoforge"
            low.contains("forge") -> "forge"
            low.contains("fabric") -> "fabric"
            low.contains("quilt") -> "quilt"
            else -> ""
        }
    }

    /** 去掉文件名里的版本段与加载器段，得到"干净的模组名"用于搜索 */
    fun cleanName(fileName: String): String {
        return fileName
            .substringBeforeLast('.')
            .replace(Regex("[_-]?(fabric|forge|neoforge|quilt)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[_-]?mc?1\\.\\d{1,2}(\\.\\d{1,2})?.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[._-]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    data class Plan(
        val fileName: String,      // 原文件
        val modName: String,       // 解析出的模组名
        val fromLoader: String,
        val toLoader: String,
        val mcVersion: String,
        val targetVersion: String, // 找到的目标版本名
        val url: String,           // 下载地址（找不到则空）
        val note: String           // 说明（为什么找不到）
    )

    /**
     * 为一批模组制定跨加载器方案。
     *
     * @param names 本地模组文件名列表
     * @param fromLoader 源加载器
     * @param toLoader 目标加载器
     * @param mc 目标 MC 版本（**必须与源一致**）
     */
    fun plan(
        ctx: android.content.Context,
        names: List<String>,
        fromLoader: String,
        toLoader: String,
        mc: String
    ): List<Plan> {
        val to = norm(toLoader)
        val out = ArrayList<Plan>()

        for (raw in names) {
            val srcMc = guessMc(raw)
            val name = cleanName(raw)

            // ---- 跨版本：直接拒绝，不做 ----
            if (srcMc.isNotBlank() && mc.isNotBlank() && srcMc != mc) {
                out.add(
                    Plan(
                        fileName = raw, modName = name,
                        fromLoader = norm(fromLoader), toLoader = to,
                        mcVersion = mc, targetVersion = "", url = "",
                        note = "跨 MC 版本不迁移（$srcMc → $mc）。版本间 API 不兼容，" +
                            "应改为「找 $mc 的新版本」，而不是搬旧文件。"
                    )
                )
                continue
            }
            if (mc.isBlank()) {
                out.add(
                    Plan(
                        fileName = raw, modName = name,
                        fromLoader = norm(fromLoader), toLoader = to,
                        mcVersion = "", targetVersion = "", url = "",
                        note = "未指定 MC 版本，无法匹配对应版本的文件"
                    )
                )
                continue
            }

            // ---- 同版本：搜同名项目，取目标加载器下的文件 ----
            val hits = try {
                ModrinthApi.search(name, mc, to, 5)
            } catch (t: Throwable) {
                emptyList<MarketMod>()
            }

            if (hits.isEmpty()) {
                out.add(
                    Plan(
                        fileName = raw, modName = name,
                        fromLoader = norm(fromLoader), toLoader = to,
                        mcVersion = mc, targetVersion = "", url = "",
                        note = "在 Modrinth 上没找到同名的 $to 版本（可能这个项目只做了 $fromLoader）"
                    )
                )
                continue
            }

            // 取第一个匹配的项目的版本文件，只留目标加载器 + 目标 MC 版本
            val proj = hits.first()
            val files = try {
                ModrinthApi.versions(proj.id, mc, to)
            } catch (t: Throwable) {
                emptyList<ModFile>()
            }
            val pick = files.firstOrNull()
            if (pick == null) {
                out.add(
                    Plan(
                        fileName = raw, modName = name,
                        fromLoader = norm(fromLoader), toLoader = to,
                        mcVersion = mc, targetVersion = "", url = "",
                        note = "找到了项目「${proj.name}」，但它没有 $mc / $to 的文件"
                    )
                )
                continue
            }

            out.add(
                Plan(
                    fileName = raw, modName = name,
                    fromLoader = norm(fromLoader), toLoader = to,
                    mcVersion = mc,
                    targetVersion = pick.version.ifBlank { pick.name },
                    url = pick.url,
                    note = "可替换为 ${proj.name} 的 $to 版本"
                )
            )
        }
        return out
    }

    /** 汇总报告 */
    fun report(plans: List<Plan>): String {
        val ok = plans.filter { it.url.isNotBlank() }
        val bad = plans.filter { it.url.isBlank() }
        return buildString {
            appendLine("共 ${plans.size} 个模组")
            appendLine("可替换 ${ok.size} 个，无法处理 ${bad.size} 个")
            appendLine()
            if (ok.isNotEmpty()) {
                appendLine("【可替换】")
                for (p in ok) {
                    appendLine("  ${p.modName}")
                    appendLine("    ${p.fromLoader} → ${p.toLoader}（MC ${p.mcVersion}）")
                    appendLine("    目标版本：${p.targetVersion}")
                }
                appendLine()
            }
            if (bad.isNotEmpty()) {
                appendLine("【无法处理】")
                for (p in bad) {
                    appendLine("  ${p.modName}：${p.note}")
                }
                appendLine()
            }
            appendLine("说明：替换后原文件会进回收站，可随时还原。")
            appendLine("跨 MC 版本的情况一律不处理，请改用「检查更新」找新版。")
        }
    }
}
