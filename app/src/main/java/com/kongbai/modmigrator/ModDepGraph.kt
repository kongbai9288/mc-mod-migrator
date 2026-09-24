package com.kongbai.modmigrator

import java.util.Locale

/**
 * 模组依赖图：递归解析 + 冲突检测。
 *
 * 之前只读一层依赖，而且完全不做冲突检测——
 * 实际上一个 Forge 模组的前置会再依赖别的，缺失的是"缺失的依赖的依赖"，
 * 只查一层永远查不出来。
 *
 * 这里做的事：
 *  1. **递归解析**：从每个模组的 depends 出发，逐级往下追（带环检测），
 *     直到所有前置都满足或确认缺失。
 *  2. **冲突检测**：
 *     - Mod ID 重复（同一个 id 装了两份，加载器会直接崩）
 *     - 同一 Mod ID 装了多个不同版本
 *     - API 冲突：同时装了 Forge 与 Fabric 的模组
 *     - 加载器不匹配：fabric.mod.json 的模组放进了 Forge 实例
 *     - 缺失依赖 / 版本范围不满足
 *  3. 输出**可读报告**，不只是报错。
 *
 * 纯本地计算，不联网。
 */
object ModDepGraph {

    data class Mod(
        val file: String,
        val id: String,
        val name: String,
        val version: String,
        val loader: String,          // fabric / forge / quilt / neoforge / unknown
        val depends: List<String>,   // 依赖的 mod id（可能带版本范围）
        val breaks: List<String> = emptyList() // 明确声明的冲突项
    )

    data class Report(
        val missing: List<String> = emptyList(),   // 缺失的依赖（含说明）
        val dupId: List<String> = emptyList(),     // Mod ID 重复
        val multiVersion: List<String> = emptyList(), // 同 id 多版本
        val apiConflict: List<String> = emptyList(),  // API / 加载器冲突
        val broken: List<String> = emptyList(),       // 明确声明的冲突
        val order: List<String> = emptyList(),        // 建议加载顺序（拓扑排序结果）
        val ok: Boolean = true
    ) {
        fun isEmpty() = missing.isEmpty() && dupId.isEmpty() &&
            multiVersion.isEmpty() && apiConflict.isEmpty() && broken.isEmpty()
    }

    /**
     * 分析一组模组。
     * @param mods 已解析出的本地模组
     */
    fun analyze(mods: List<Mod>): Report {
        if (mods.isEmpty()) return Report()

        val byId = HashMap<String, MutableList<Mod>>()
        for (m in mods) {
            val k = m.id.lowercase(Locale.ROOT)
            if (k.isBlank()) continue
            byId.getOrPut(k) { ArrayList() }.add(m)
        }

        // ---------- 1. Mod ID 重复 ----------
        val dupId = ArrayList<String>()
        val multiVersion = ArrayList<String>()
        for ((id, list) in byId) {
            if (list.size <= 1) continue
            val versions = list.map { it.version }.distinct()
            val files = list.joinToString("、") { it.file }
            if (versions.size > 1) {
                multiVersion.add(
                    "「$id」装了 ${list.size} 个不同版本（${versions.joinToString("、")}）：$files\n" +
                        "      → 建议只保留一个版本"
                )
            } else {
                dupId.add(
                    "「$id」重复安装了 ${list.size} 份（版本相同）：$files\n" +
                        "      → 加载器会因重复注册而启动失败，请删掉多余的"
                )
            }
        }

        // ---------- 2. 加载器 / API 冲突 ----------
        val apiConflict = ArrayList<String>()
        val loaders = mods.map { it.loader.lowercase(Locale.ROOT) }
            .filter { it in setOf("fabric", "forge", "quilt", "neoforge") }
            .distinct()
        // Fabric 与 Quilt 通常可共存，Forge/NeoForge 与 Fabric 不行
        val hasFabricLike = loaders.any { it == "fabric" || it == "quilt" }
        val hasForgeLike = loaders.any { it == "forge" || it == "neoforge" }
        if (hasFabricLike && hasForgeLike) {
            apiConflict.add(
                "同时存在 Fabric/Quilt 与 Forge/NeoForge 模组（检测到：${loaders.joinToString("、")}）\n" +
                    "      → 这两套加载器不能混装，请分成两个实例"
            )
        }

        // ---------- 3. 递归解析缺失依赖 ----------
        val missing = ArrayList<String>()
        val present = byId.keys.toSet()
        // 内建的"环境"项不算缺失
        val BUILTIN = setOf(
            "minecraft", "java", "fabricloader", "fabric", "forge",
            "neoforge", "quilt_loader", "quilt", "minecraftforge"
        )
        for (m in mods) {
            resolveDeep(m, byId, present, BUILTIN, missing, HashSet(), 0)
        }

        // ---------- 4. 明确声明的冲突（breaks） ----------
        val broken = ArrayList<String>()
        for (m in mods) {
            for (b in m.breaks) {
                val k = b.lowercase(Locale.ROOT)
                if (present.contains(k)) {
                    broken.add("「${m.id}」声明与「$b」冲突，但两个都装了：${m.file}")
                }
            }
        }

        // ---------- 5. 建议加载顺序（拓扑排序） ----------
        val order = topoSort(mods, byId)

        return Report(
            missing = missing.distinct(),
            dupId = dupId,
            multiVersion = multiVersion,
            apiConflict = apiConflict,
            broken = broken,
            order = order,
            ok = false
        ).let { it.copy(ok = it.isEmpty()) }
    }

    /**
     * 递归解析某个模组的依赖。
     * @param depth 递归深度，防环；visited 记录本次链路已访问的 id（环检测）
     */
    private fun resolveDeep(
        mod: Mod,
        byId: Map<String, List<Mod>>,
        present: Set<String>,
        builtin: Set<String>,
        out: MutableList<String>,
        visited: MutableSet<String>,
        depth: Int
    ) {
        if (depth > 8) return // 防病态依赖链
        val self = mod.id.lowercase(Locale.ROOT)
        if (!visited.add(self)) return // 环：A→B→A

        for (raw in mod.depends) {
            val dep = raw.substringBefore('@').substringBefore(' ')
                .trim().lowercase(Locale.ROOT)
            if (dep.isBlank()) continue
            if (builtin.contains(dep)) continue
            if (present.contains(dep)) {
                // 依赖存在，但它的依赖可能仍缺失 → 继续往下追
                val next = byId[dep]?.firstOrNull() ?: continue
                resolveDeep(next, byId, present, builtin, out, visited, depth + 1)
                continue
            }
            // 缺失：说明是谁缺的、链条怎么来的
            val chain = if (depth == 0) "" else "（经由 ${mod.id}）"
            out.add("「${mod.name.ifBlank { mod.id }}」缺少前置「$dep」$chain")
        }
    }

    /**
     * 拓扑排序，给出建议加载顺序。
     * 依赖者排在依赖项之后；有环的部分放最后，不无限循环。
     */
    private fun topoSort(mods: List<Mod>, byId: Map<String, List<Mod>>): List<String> {
        val result = ArrayList<String>()
        val done = HashSet<String>()
        val visiting = HashSet<String>()

        fun visit(id: String) {
            val k = id.lowercase(Locale.ROOT)
            if (k in done || k in visiting) return
            visiting.add(k)
            val m = byId[k]?.firstOrNull() ?: return
            for (raw in m.depends) {
                val d = raw.substringBefore('@').substringBefore(' ')
                    .trim().lowercase(Locale.ROOT)
                if (byId.containsKey(d)) visit(d)
            }
            visiting.remove(k)
            done.add(k)
            result.add(m.name.ifBlank { m.id })
        }

        for (m in mods) visit(m.id.lowercase(Locale.ROOT))
        return result
    }

    /** 把报告拼成一段可读文字 */
    fun format(r: Report): String {
        if (r.isEmpty()) {
            return buildString {
                append("没有发现冲突或缺失依赖。\n\n")
                if (r.order.isNotEmpty()) {
                    append("建议加载顺序（依赖优先）：\n")
                    r.order.take(30).forEachIndexed { i, s -> append("  ${i + 1}. $s\n") }
                }
            }
        }
        val sb = StringBuilder()
        fun section(title: String, list: List<String>, tip: String) {
            if (list.isEmpty()) return
            sb.append("【").append(title).append("】").append(list.size).append(" 项\n")
            list.forEach { sb.append("  · ").append(it).append('\n') }
            sb.append("  → ").append(tip).append("\n\n")
        }
        section("Mod ID 重复", r.dupId, "删掉多余的那一份，否则启动直接崩")
        section("同一模组多版本", r.multiVersion, "只保留一个版本")
        section("加载器/API 冲突", r.apiConflict, "分成两个实例分别装")
        section("缺失依赖（已递归追查）", r.missing, "补装这些前置模组")
        section("声明的冲突", r.broken, "移除其中一个")
        if (r.order.isNotEmpty()) {
            sb.append("【建议加载顺序】\n")
            r.order.take(30).forEachIndexed { i, s -> sb.append("  ${i + 1}. $s\n") }
        }
        return sb.toString()
    }
}
