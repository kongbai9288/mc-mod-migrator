package com.kongbai.modmigrator

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.viaversion.nbt.tag.CompoundTag
import java.io.File

/**
 * 区块图形编辑器（.mca 区域文件）。
 *
 * 解析走 [McaEdit]，画图走 [McaRender]，都是本工程自己的代码 ——
 * 不再依赖移植的桌面库：那种库靠反射注册各版本实现，
 * 一旦注册不上就静默画不出东西，出了事完全没法定位。
 *
 * 用法：区域总览 → 点一下进某个区块 → 上下翻层看 → 可删 → 保存写回。
 *
 * ⚠️ 区域文件是存档本体，改错了就是毁档：
 *   · 首次打开先给原文件留一份 .bak；
 *   · 删区块前二次确认。
 */
class ChunkMapActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PATH = "mca_path"
        private const val EXTRA_URI = "mca_uri"

        fun open(ctx: Context, f: File) {
            ctx.startActivity(
                Intent(ctx, ChunkMapActivity::class.java).putExtra(EXTRA_PATH, f.absolutePath)
            )
        }

        fun openUri(ctx: Context, uri: Uri) {
            ctx.startActivity(
                Intent(ctx, ChunkMapActivity::class.java).putExtra(EXTRA_URI, uri.toString())
            )
        }

        /** 1.18 起区段号从 -4 开始，之前是 0 */
        private const val SEC_MIN = -4
        private const val SEC_MAX = 19

        private const val IMG = 512
    }

    private var region: McaEdit.Region? = null
    /** 已解析的区块，按槽位缓存 —— 区域总览和翻层都要反复用 */
    /**
     * 已解析的区块缓存 —— **必须设上限**。
     *
     * 之前是无上限的 HashMap：区域总览一次要把整个区域的区块全解析并常驻。
     * 实测一个区域（1024 槽位）展开后是 20MB 上下的 CompoundTag ——
     * 每个区块 24 个 section，每个 section 又各自带
     * block_states / biomes 两套 palette + data 子结构。
     * 本就吃紧的设备上这会直接把堆撑爆，表现就是"页面一片空白"、
     * 或者一点进来就闪退，看起来完全像"编辑器坏了"。
     *
     * 而真正需要留着的只有「正在翻层的那一个区块」。
     * 区域总览是扫一遍、画完就丢（见 [renderRegion]）。
     * 这里按访问顺序保留最近 48 个，翻层与回看都够用，内存恒定。
     */
    private val cache = object : LinkedHashMap<Int, CompoundTag?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, CompoundTag?>?): Boolean =
            size > 48
    }
    private var present: List<Int> = emptyList()
    /** 页面已销毁：后台解析线程据此尽早退出，不再往死掉的界面里塞图。 */
    @Volatile private var cancelled = false

    private var work: File? = null
    private var srcPath: String = ""
    private var srcUri: Uri? = null

    private var slot = -1
    private var secY = SEC_MIN
    private var layerMode = false
    private var dirty = false

    private var tapX = 0f
    private var tapY = 0f

    private lateinit var tvInfo: TextView
    private lateinit var image: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "区块编辑器"

        srcPath = intent.getStringExtra(EXTRA_PATH) ?: ""
        val u = intent.getStringExtra(EXTRA_URI)
        srcUri = if (u.isNullOrBlank()) null else Uri.parse(u)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (10 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        setContentView(
            root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        tvInfo = TextView(this).apply { textSize = 12f; text = "正在读取区域文件…" }
        root.addView(tvInfo)

        image = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        root.addView(image)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        root.addView(row)
        row.addView(MaterialButton(this).apply {
            text = "上一层"
            setOnClickListener { step(-1) }
        })
        row.addView(MaterialButton(this).apply {
            text = "下一层"
            setOnClickListener { step(1) }
        })
        row.addView(MaterialButton(this).apply {
            text = "整列"
            setOnClickListener { layerMode = false; render() }
        })
        row.addView(MaterialButton(this).apply {
            text = "返回全区"
            setOnClickListener { backToRegion() }
        })

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        root.addView(row2)
        row2.addView(MaterialButton(this).apply {
            text = "删除区块"
            setOnClickListener { askDelete() }
        })
        row2.addView(MaterialButton(this).apply {
            text = "保存"
            setOnClickListener { save() }
        })

        image.setOnTouchListener { v, ev ->
            if (ev.action == android.view.MotionEvent.ACTION_UP) {
                tapX = ev.x; tapY = ev.y
                v.performClick()
            }
            true
        }
        image.setOnClickListener { onTap(it) }

        Thread { prepare() }.start()
    }

    private fun post(msg: String) = runOnUiThread {
        tvInfo.text = msg
    }

    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------ 载入

    /**
     * SAF 的 content:// 拿不到真实 File，所以统一先落到自己的缓存目录，
     * 改完再写回原处。顺带给原文件留一份 .bak。
     */
    private fun prepare() {
        val cacheDir = File(filesDir, "mca").apply { mkdirs() }
        val dst = File(cacheDir, "work.mca")
        try {
            val bytes = if (srcPath.isNotBlank()) File(srcPath).readBytes()
            else contentResolver.openInputStream(srcUri!!)?.use { it.readBytes() }
            if (bytes == null) {
                post("读不了这个区域文件")
                return
            }
            if (bytes.size < 8192) {
                post("这个文件太小（${bytes.size} 字节），不像有效的 .mca")
                return
            }
            dst.writeBytes(bytes)
            if (srcPath.isNotBlank()) {
                val bak = File("$srcPath.bak")
                if (!bak.exists()) runCatching { bak.writeBytes(bytes) }
            }
        } catch (t: Throwable) {
            Err.fail(t, "准备区域文件")
            post("读取失败：${t.message ?: t.javaClass.simpleName}")
            return
        }
        val r = try {
            McaEdit.Region(dst.readBytes())
        } catch (t: Throwable) {
            Err.fail(t, "打开区域文件")
            post("打开失败：${t.message ?: t.javaClass.simpleName}")
            return
        }
        region = r
        present = r.present()
        work = dst
        if (present.isEmpty()) {
            post("这个区域文件里没有任何区块（空的）")
            return
        }
        runOnUiThread {
            slot = -1
            layerMode = false
            render()
        }
    }

    private fun chunkAt(s: Int): CompoundTag? {
        if (s !in 0 until 1024) return null
        synchronized(cache) { if (cache.containsKey(s)) return cache[s] }
        val c = try {
            region?.chunk(s)
        } catch (t: Throwable) {
            null
        }
        synchronized(cache) { cache[s] = c }
        return c
    }

    // ------------------------------------------------------------------ 渲染

    private fun render() {
        val r = region
        if (r == null) { post("区域文件还没准备好"); return }
        if (slot < 0) {
            renderRegion()
        } else {
            renderChunk()
        }
    }

    private fun renderRegion() {
        val r = region ?: return
        post("正在画区域总览（${present.size} 个区块）…")
        Thread {
            val px = IntArray(IMG * IMG)
            // 背景：深灰，空区块的位置就留着，一眼能看出哪些没生成
            for (i in px.indices) px[i] = 0xFF141414.toInt()
            var drawn = 0
            var air = 0
            var failed = 0
            val t0 = System.currentTimeMillis()
            var n = 0
            for (s in present) {
                if (cancelled) return@Thread
                // ⚠️ 刻意不走 chunkAt：总览每个区块只用一次，画完就丢。
                // 走缓存的话整区域的 CompoundTag 会全部常驻，
                // 那是"点进来就空白/闪退"的直接原因。
                val c = try {
                    r.chunk(s)
                } catch (t: Throwable) {
                    null
                }
                if (c == null || c.isEmpty()) {
                    failed++
                } else {
                    val cx = s and 31
                    val cz = (s shr 5) and 31
                    if (McaRender.drawTop(c, px, IMG, cx * 16, cz * 16, 1)) drawn++ else air++
                }
                n++
                // 一个区域上千个区块，中途给进度，否则看着像卡死
                if (n % 64 == 0) {
                    val k = n
                    post("正在画区域总览… $k / ${present.size}")
                }
            }
            if (cancelled) return@Thread
            val bmp = Bitmap.createBitmap(px, IMG, IMG, Bitmap.Config.ARGB_8888)
            val sec = (System.currentTimeMillis() - t0) / 1000
            val total = present.size
            runOnUiThread {
                image.setImageBitmap(bmp)
                post(
                    "区域总览：共 $total 个区块，画出 $drawn 个" +
                        (if (air > 0) "，全是空气 $air 个" else "") +
                        (if (failed > 0) "，读不出 $failed 个" else "") +
                        "（$sec 秒）\n点一下进对应区块" +
                        if (drawn == 0 && air > 0)
                            "\n这些区块存在，但地形还没生成过" +
                                "\n（若这是 poi/ 或 entities/ 下的文件，它本来就不存方块）"
                        else ""
                )
            }
        }.start()
    }

    private fun renderChunk() {
        val c = chunkAt(slot)
        if (c == null) { post("这个区块读不出来（可能已损坏或用了不支持的压缩）"); return }
        val cx = slot and 31
        val cz = (slot shr 5) and 31
        val r = region ?: return
        // 区域坐标 → 世界区块坐标：r.x.z.mca 的 x/z 从文件名来，这里用相对值兜底
        val scale = IMG / 16
        Thread {
            val px = IntArray(IMG * IMG)
            for (i in px.indices) px[i] = 0xFF101010.toInt()
            val ok = if (layerMode) McaRender.drawLayer(c, secY, px, IMG, 0, 0, scale)
            else McaRender.drawTop(c, px, IMG, 0, 0, scale)
            val bmp = Bitmap.createBitmap(px, IMG, IMG, Bitmap.Config.ARGB_8888)
            val dv = try {
                c.getIntTag("DataVersion")?.getValue()
            } catch (_: Throwable) { null }
            val secs = McaEdit.sections(c).size
            runOnUiThread {
                image.setImageBitmap(bmp)
                post(
                    "区块 ($cx, $cz)　槽位 $slot\n" +
                    (if (layerMode) "第 $secY 层（区段号）" else "整列俯视图") +
                    "　段数 $secs" + (if (dv != null) "　DataVersion $dv" else "") +
                    if (secs == 0) "\n（这个区块解析出来是空的，可能压缩方式不支持）" else
                        if (!ok) "\n（这一层没画出方块，可能是空的）" else ""
                )
            }
        }.start()
    }

    private fun onTap(v: android.view.View) {
        val r = region ?: return
        if (slot >= 0) return
        val w = v.width.toFloat()
        val h = v.height.toFloat()
        if (w <= 0 || h <= 0) return
        // ImageView 是 FIT_CENTER，图片实际区域可能比 view 小，按等比换算
        val scale = minOf(w / IMG, h / IMG)
        val offX = (w - IMG * scale) / 2f
        val offY = (h - IMG * scale) / 2f
        val ix = ((tapX - offX) / scale).toInt()
        val iy = ((tapY - offY) / scale).toInt()
        if (ix !in 0 until IMG || iy !in 0 until IMG) return
        val cx = ix / 16
        val cz = iy / 16
        val s = cz * 32 + cx
        if (!present.contains(s)) {
            toast("这里没有区块（还没生成过）")
            return
        }
        slot = s
        layerMode = false
        secY = SEC_MIN
        render()
    }

    private fun backToRegion() {
        slot = -1
        layerMode = false
        render()
    }

    /** 上下翻层，自动跳过整片空的层 —— 否则要按二十几次才找到有东西的一层。 */
    private fun step(dir: Int) {
        val c = chunkAt(slot)
        if (slot < 0 || c == null) { toast("先点一个区块"); return }
        if (!layerMode) {
            layerMode = true
            // 从当前世界的实际段里找一个有内容的起点
            val ys = McaEdit.sections(c).map { it.y }
            if (ys.isEmpty()) { toast("这个区块没有段数据"); return }
            secY = if (dir > 0) ys.minOrNull()!! else ys.maxOrNull()!!
            if (McaRender.layerHasBlocks(c, secY)) { render(); return }
        }
        var y = secY
        repeat(48) {
            y += dir
            if (y < SEC_MIN || y > SEC_MAX) return@repeat
            if (McaRender.layerHasBlocks(c, y)) {
                secY = y
                layerMode = true
                render()
                return
            }
        }
        toast("这个方向上没有更多有内容的层了")
    }

    // ------------------------------------------------------------------ 删除 / 保存

    private fun askDelete() {
        val r = region ?: return
        if (slot < 0) { toast("先在总览里点一个区块"); return }
        val cx = slot and 31
        val cz = (slot shr 5) and 31
        MaterialAlertDialogBuilder(this)
            .setTitle("删除区块 ($cx, $cz)？")
            .setMessage(
                "这个区块的数据会被整个抹掉。\n\n" +
                "再次进入该区域时，游戏会按当前版本重新生成地形 —— " +
                "之前在这里建过的东西不会回来。\n\n" +
                "保存前不会写入原文件，可以先反悔。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                if (r.remove(slot)) {
                    cache.remove(slot)
                    present = r.present()
                    dirty = true
                    toast("已删除，记得点保存")
                    backToRegion()
                }
            }
            .show()
    }

    private fun save() {
        val r = region ?: return
        val dst = work ?: return
        if (!dirty) { toast("没有改动"); return }
        Thread {
            try {
                val bytes = r.build()
                dst.writeBytes(bytes)
                if (srcPath.isNotBlank()) File(srcPath).writeBytes(bytes)
                else contentResolver.openOutputStream(srcUri!!, "wt")?.use { it.write(bytes) }
                runOnUiThread {
                    dirty = false
                    toast("已写回（${bytes.size / 1024} KB）")
                    post("已保存")
                }
            } catch (t: Throwable) {
                Err.fail(t, "保存区域文件")
                runOnUiThread { post("保存失败：${t.message ?: t.javaClass.simpleName}") }
            }
        }.start()
    }

    override fun onDestroy() {
        cancelled = true
        super.onDestroy()
        synchronized(cache) { cache.clear() }
        region = null
    }
}
