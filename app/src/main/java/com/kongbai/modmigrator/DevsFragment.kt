package com.kongbai.modmigrator

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

class DevsFragment : Fragment() {

    private val exec = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    /** 连点计数：点满 5 次也能唤出彩蛋 */
    private var eggClicks = 0

    /**
     * 隐藏彩蛋。长按开发者名单（或连点 5 次）触发。
     * 内容每次随机换一条 MC 冷知识，外加版本信息与致谢。
     */
    private fun showEgg() {
        val ctx = context ?: return
        val facts = listOf(
            "Minecraft 的唱片「11」里藏着一段脚步声与低语，\n至今没人完全解读清楚。",
            "「Herobrine」从未在 Minecraft 正式版里出现过，\n官方更新日志多次以「移除了 Herobrine」开玩笑。",
            "苦力怕（Creeper）的诞生源于一次建模事故：\n作者本想做猪，结果把身体拉长了。",
            "末影人的声音是把现实里的录音倒放再处理出来的。",
            "Minecraft 最早的名字叫「Cave Game」，\n后来才改成 Minecraft: Order of the Stone。",
            "1.13 更新把「方块 ID」系统彻底重写，\n这也是为什么老模组在那之后几乎全部失效。",
            "下界传送门在 Java 版与基岩版的坐标换算不同，\n所以跨版本迁移存档要格外小心。"
        )
        val fact = facts[(System.currentTimeMillis() % facts.size).toInt()]
        val pkg = try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "${pi.versionName}（${pi.packageName}）"
        } catch (t: Throwable) {
            "未知"
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("🥚 你发现了彩蛋")
            .setMessage(
                "$fact\n\n" +
                    "—— 致每一位把配置一个个搬过去的 MC 玩家。\n\n" +
                    "版本：$pkg\n" +
                    "本工具由 kongbai 与贡献者共同维护，欢迎在仓库提 Issue 与 PR。"
            )
            .setPositiveButton("再抽一条") { _, _ -> showEgg() }
            .setNegativeButton("关闭", null)
            .show()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_devs, container, false)
        val box = v.findViewById<LinearLayout>(R.id.boxDevs)
        val tv = v.findViewById<TextView>(R.id.tvDevsState)
        val ctx = requireContext()

        // 彩蛋：长按名单区域弹出（连点也会触发，方便发现）
        box.setOnLongClickListener { showEgg(); true }
        tv.setOnLongClickListener { showEgg(); true }
        eggClicks = 0
        box.setOnClickListener {
            eggClicks++
            if (eggClicks >= 5) {
                eggClicks = 0
                showEgg()
            }
        }

        tv.text = "正在读取名单…"
        exec.execute {
            try {
                val list = DevTeam.load(ctx)
                safePost(handler) {
                    box.removeAllViews()
                    for (d in list) {
                        val row = TextView(ctx)
                        row.text = "${d.name} · ${d.role}"
                        row.textSize = 14f
                        row.setPadding(0, 10, 0, 10)
                        if (d.url.isNotBlank()) {
                            row.setOnClickListener {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(d.url)))
                            }
                        }
                        box.addView(row)
                    }
                    tv.text = "共 ${list.size} 位（点击可打开主页）"
                }

            } catch (t: Throwable) {
                // 后台异常不崩进程
            }
        }
        return v
    }
}
