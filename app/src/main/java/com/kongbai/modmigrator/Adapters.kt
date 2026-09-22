package com.kongbai.modmigrator

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class ModAdapter(
    private val items: MutableList<ModEntry>,
    private val onAction: (ModEntry) -> Unit
) : RecyclerView.Adapter<ModAdapter.VH>() {

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
    }
}

class MarketAdapter(
    private val items: MutableList<MarketMod>,
    private val onInstall: (MarketMod) -> Unit,
    private val onOpen: (MarketMod) -> Unit,
    private val onTranslate: (MarketMod) -> Unit
) : RecyclerView.Adapter<MarketAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.ivIcon)
        val name: TextView = v.findViewById(R.id.tvName)
        val meta: TextView = v.findViewById(R.id.tvMeta)
        val status: TextView = v.findViewById(R.id.tvStatus)
        val action: Button = v.findViewById(R.id.btnAction)
        val trans: Button = v.findViewById(R.id.btnTrans)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_mod, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val m = items[pos]
        h.name.text = m.name
        h.meta.text = "${m.source} · 下载量 ${m.downloads}"
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
        h.action.text = "安装"
        h.action.setOnClickListener { onInstall(m) }
        h.itemView.setOnClickListener { onOpen(m) }
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
