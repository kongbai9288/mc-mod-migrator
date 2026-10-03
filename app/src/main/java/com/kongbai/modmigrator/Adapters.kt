package com.kongbai.modmigrator

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class ModAdapter(
    private val items: MutableList<ModEntry>,
    private val onAction: (ModEntry) -> Unit,
    private val onDetail: ((ModEntry) -> Unit)? = null
) : RecyclerView.Adapter<ModAdapter.VH>() {

    /** 图标异步加载线程池。列表滚动时不会阻塞。 */
    private val iconPool = java.util.concurrent.Executors.newFixedThreadPool(2)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.ivIcon)
        val name: TextView = v.findViewById(R.id.tvName)
        val meta: TextView = v.findViewById(R.id.tvMeta)
        val status: TextView = v.findViewById(R.id.tvStatus)
        val action: Button = v.findViewById(R.id.btnAction)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_mod, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val m = items[pos]
        h.name.text = m.name.ifBlank { m.fileName }
        h.meta.text = "当前 ${m.currentVersion.ifBlank { "未知" }}  →  目标 ${m.targetVersion.ifBlank { "无" }}"
        h.status.text = m.status
        h.action.text = if (m.targetUrl.isBlank()) "跳过" else "下载"
        h.action.isEnabled = m.targetUrl.isNotBlank()
        h.action.setOnClickListener { onAction(m) }
        // 图标：优先从本地 jar 里提取（离线可用、不耗流量），
        // 取不到就保留默认的方块占位图。
        h.icon.tag = m.uri
        h.icon.setImageResource(R.drawable.ic_extension)
        if (m.uri.isNotBlank()) {
            iconPool.submit {
                val bmp = try {
                    ModIcons.ofUri(h.itemView.context, m.uri)
                } catch (t: Throwable) {
                    null
                }
                if (bmp != null && h.icon.tag == m.uri) {
                    mainHandler.post {
                        if (h.icon.tag == m.uri) h.icon.setImageBitmap(bmp)
                    }
                }
            }
        }
        // 整行点击 = 打开模组详情页（有地址才跳）
        h.itemView.setOnClickListener {
            onDetail?.invoke(m)
        }
    }
}

class MarketAdapter(
    private val items: MutableList<MarketMod>,
    private val onInstall: (MarketMod) -> Unit,
    private val onOpen: (MarketMod) -> Unit,
    private val onTranslate: (MarketMod) -> Unit,
    private val onFav: (MarketMod) -> Unit = {},
    private val isFav: (MarketMod) -> Boolean = { false },
    /**
     * 这个模组是不是已经装在 mods 目录里了。
     *
     * ⚠️ 之前下载完**没有任何视觉反馈** —— 按钮还是"安装"，
     * 用户不知道到底装没装成，只能自己再去目录里翻。
     * 现在装过的按钮变成"已安装"，一眼能看出来。
     *
     * 用 lambda 而不是 MarketMod 上的字段：装没装是**外部状态**，
     * 存成字段会和真实目录不同步（比如在别处把 jar 删了）。
     */
    private val isInstalled: (MarketMod) -> Boolean = { false },
    /**
     * 当前选择的加载器，用来判断"这个模组支不支持你选的环境"。
     * 用 lambda 而不是固定值：用户切换加载器后不用重建 Adapter。
     */
    private val currentLoader: () -> String = { "auto" },
    /**
     * 明知不支持仍要安装时的回调（点按钮 → 弹警告 → 确认 → 走这里）。
     * 与 onInstall 分开是为了**不影响批量下载**：
     * 批量下载直接调 onInstall，不会撞上确认弹窗。
     */
    private val onInstallMismatch: (MarketMod) -> Unit = onInstall
) : RecyclerView.Adapter<MarketAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.ivIcon)
        val name: TextView = v.findViewById(R.id.tvName)
        val meta: TextView = v.findViewById(R.id.tvMeta)
        val status: TextView = v.findViewById(R.id.tvStatus)
        val action: Button = v.findViewById(R.id.btnAction)
        val trans: Button = v.findViewById(R.id.btnTrans)
        val rowLoaders: WrapRow = v.findViewById(R.id.rowLoaders)
        val downloads: TextView = v.findViewById(R.id.tvDownloads)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_mod, parent, false)
        return VH(v)
    }

    /** 把大数字压成中文习惯的短写法：12.8万 / 1.2亿 */
    private fun compactCount(n: Long): String {
        if (n <= 0) return "0"
        return when {
            n >= 100_000_000L -> "%.1f亿".format(n / 100_000_000.0)
            n >= 10_000L -> {
                val v = n / 10_000.0
                // 1.0万 这种小数点后的 0 没意义，整数就显示整数
                if (v >= 100) "%.0f万".format(v) else "%.1f万".format(v)
            }
            else -> {
                // 千位分隔，避免 1234 和 12345 混在一起看错量级
                java.text.NumberFormat.getIntegerInstance(java.util.Locale.CHINA).format(n)
            }
        }
    }

    /**
     * 画加载器图标，后面跟下载量。
     *
     * 图标是纯黑线稿（透明底），这里统一染成次要文字色：
     * 一是跟"平台 · 下载量"同级，不抢模组名的视觉重心；
     * 二是深浅两套主题下都看得清。
     *
     * ⚠️ 之前最多只画 4 个，剩下的用 "+N" 带过 ——
     * 支持 5 个以上的模组看到的图标是**不全的**，
     * 而"+2"也不告诉你到底是哪两个。
     * 现在容器换成了会自动换行的 [WrapRow]，全部画出来。
     */
    private fun renderLoaderIcons(h: VH, lds: List<String>, downloads: Long) {
        h.rowLoaders.removeAllViews()
        val ctx = h.itemView.context
        val tint = try {
            if (android.os.Build.VERSION.SDK_INT >= 23) ctx.getColor(R.color.textSecondary)
            else @Suppress("DEPRECATION") ctx.resources.getColor(R.color.textSecondary)
        } catch (t: Throwable) {
            android.graphics.Color.GRAY
        }
        val d = ctx.resources.displayMetrics.density
        val size = (d * 15).toInt()
        for (ld in lds) {
            val iv = ImageView(ctx)
            iv.setImageResource(Loaders.icon(ld))
            iv.setColorFilter(tint)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.contentDescription = Loaders.label(ld)
            // WrapRow 只认 MarginLayoutParams
            val lp = ViewGroup.MarginLayoutParams(size, size)
            lp.marginEnd = (d * 3).toInt()
            iv.layoutParams = lp
            h.rowLoaders.addView(iv)
        }
        h.downloads.text = "${compactCount(downloads)} 次下载"
        val dlp = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dlp.marginStart = (d * 2).toInt()
        h.downloads.layoutParams = dlp
        h.rowLoaders.addView(h.downloads)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val m = items[pos]
        // 收藏的加个星标，一眼能认出来
        h.name.text = (if (isFav(m)) "★ " else "") + m.name
        // ⚠️ 之前直接打印原始数字（如 "下载量 12847392"）。
        // 位数长、没分隔，在列表里横向占位很宽，
        // 把模组名和简介挤得只剩一点点，看列表很费劲。
        // 改成中文习惯的万/亿，一眼能比较量级又不挡视线。
        // ── 平台 + 加载器图标 + 下载量 ──────────────────────
        // 模组声明了支持的加载器时，图标单独占一行（平台名下方），
        // 下载量跟在图标后面。没声明时退回原来的"平台 · 下载量"。
        // 显示前再整理一次（丢掉认不出来的、去重、稳定排序）：
        // 收藏夹是从本地 JSON 恢复的、后端来源也不一定解析过 loaders，
        // 只在解析处清理的话，这几条路径仍可能带进重复的「自动」。
        val lds = Loaders.clean(m.loaders)
        if (lds.isEmpty()) {
            h.rowLoaders.visibility = View.GONE
            h.meta.text = "${m.source} · ${compactCount(m.downloads)} 次下载"
        } else {
            h.rowLoaders.visibility = View.VISIBLE
            h.meta.text = m.source
            renderLoaderIcons(h, lds, m.downloads)
        }
        if (m.summaryZh.isNotBlank()) {
            h.status.text = m.summaryZh
            h.trans.text = "原文"
        } else {
            h.status.text = m.summary
            h.trans.text = "译"
        }
        h.trans.visibility = View.VISIBLE
        h.trans.setOnClickListener {
            if (m.summaryZh.isNotBlank()) {
                m.summaryZh = ""
                notifyItemChanged(pos)
            } else {
                onTranslate(m)
            }
        }
        if (m.iconUrl.isNotBlank()) {
            h.icon.load(m.iconUrl) {
                crossfade(true)
            }
        } else {
            h.icon.setImageResource(R.drawable.ic_extension)
        }
        // ── 安装按钮：不支持当前加载器时置灰但仍可点 ──────────
        // 用"变灰 + 点弹警告"而不是 setEnabled(false)：
        // 后者点了完全没反应，用户只会以为按钮坏了。
        val cur = Loaders.normalize(currentLoader())
        val mismatch = lds.isNotEmpty() && cur != "auto" && !lds.contains(cur)
        if (isInstalled(m)) {
            // 装过就标出来。仍然允许再点（可能是想覆盖更新），
            // 但不禁用 —— 禁用后点了没反应，用户只会以为按钮坏了。
            h.action.text = "已安装"
            h.action.alpha = 0.6f
            h.action.setOnClickListener { onInstall(m) }
        } else if (mismatch) {
            h.action.text = "安装?"
            h.action.alpha = 0.45f
            h.action.setOnClickListener {
                val sup = lds.joinToString("、") { Loaders.label(it) }
                com.google.android.material.dialog.MaterialAlertDialogBuilder(h.itemView.context)
                    .setTitle("这个模组没有 ${Loaders.label(cur)} 版")
                    .setMessage(
                        "《${m.name}》支持的加载器：${sup}\n\n" +
                            "当前选的是 ${Loaders.label(cur)}，" +
                            "装上去大概率进不去游戏。\n\n仍要安装吗？"
                    )
                    .setNegativeButton("取消", null)
                    .setPositiveButton("仍要安装") { _, _ -> onInstallMismatch(m) }
                    .show()
            }
        } else {
            h.action.text = "安装"
            h.action.alpha = 1f
            h.action.setOnClickListener { onInstall(m) }
        }
        h.itemView.setOnClickListener { onOpen(m) }
        // 长按收藏 / 取消收藏
        h.itemView.setOnLongClickListener {
            onFav(m)
            true
        }
    }
}

class LinkAdapter(
    private val items: MutableList<MarkedLink>,
    private val onDownload: (MarkedLink) -> Unit,
    private val onDelete: (MarkedLink) -> Unit
) : RecyclerView.Adapter<LinkAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.tvTitle)
        val url: TextView = v.findViewById(R.id.tvUrl)
        val download: Button = v.findViewById(R.id.btnDownload)
        val delete: Button = v.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_link, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val l = items[pos]
        h.title.text = l.title.ifBlank { l.url }
        h.url.text = l.url
        h.download.setOnClickListener { onDownload(l) }
        h.delete.setOnClickListener { onDelete(l) }
    }
}

class DeviceAdapter(
    private val items: MutableList<RemoteDevice>,
    private val onRestore: (RemoteDevice) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val device: TextView = v.findViewById(R.id.tvDevice)
        val time: TextView = v.findViewById(R.id.tvTime)
        val restore: Button = v.findViewById(R.id.btnRestore)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val d = items[pos]
        h.device.text = "${d.label} · ${d.modCount} 个模组"
        h.time.text = "${d.time} · MC ${d.mcVersion.ifBlank { "?" }} · ${d.loader.ifBlank { "auto" }}"
        h.restore.setOnClickListener { onRestore(d) }
    }
}

class UpdateAdapter(
    private val items: MutableList<PanelFile>,
    private val onDownload: (PanelFile) -> Unit
) : RecyclerView.Adapter<UpdateAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tvName)
        val meta: TextView = v.findViewById(R.id.tvMeta)
        val status: TextView = v.findViewById(R.id.tvStatus)
        val action: Button = v.findViewById(R.id.btnAction)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_mod, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val f = items[pos]
        h.name.text = f.name
        h.meta.text = "${f.kind} · ${f.size / 1024}KB · ${f.path}"
        h.status.text = f.status
        h.action.text = if (f.latestUrl.isBlank()) "无更新" else "下载"
        if (h.itemView.findViewById<View>(R.id.btnTrans) != null) {
            h.itemView.findViewById<View>(R.id.btnTrans).visibility = View.GONE
        }
        h.action.isEnabled = f.latestUrl.isNotBlank()
        h.action.setOnClickListener { onDownload(f) }
    }
}

class PluginAdapter(
    private val items: MutableList<PluginApi.Plugin>,
    private val onRun: (PluginApi.Plugin) -> Unit
) : RecyclerView.Adapter<PluginAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tvName)
        val meta: TextView = v.findViewById(R.id.tvMeta)
        val status: TextView = v.findViewById(R.id.tvStatus)
        val action: Button = v.findViewById(R.id.btnAction)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_mod, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val p = items[pos]
        h.name.text = p.label
        h.meta.text = p.pkg
        h.status.text = p.desc.ifBlank { "点右侧按钮触发动作" }
        h.itemView.findViewById<View>(R.id.btnTrans)?.visibility = View.GONE
        h.action.text = "运行"
        h.action.setOnClickListener { onRun(p) }
    }
}
