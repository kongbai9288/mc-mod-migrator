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
import net.querz.mcaselector.io.mca.RegionMCAFile
import java.io.File

/**
 * 区块图形编辑器（.mca 区域文件）。
 *
 * 渲染与解析交给 MCA Selector（见 [McaBridge]），
 * 这里只负责：把区域文件画成俯视图 → 点区块 → 翻层看 → 删区块 → 写回。
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
    }

    private var mca: RegionMCAFile? = null
    private var work: File? = null
    private var srcPath: String = ""
    private var srcUri: Uri? = null

    private var slot = -1
    private var secY = SEC_MIN
    private var layerMode = false
    private var dirty = false

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

        tvInfo = TextView(this).apply { textSize = 12f }
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

        Thread { prepare() }.start()
    }

    // ------------------------------------------------------------------ 载入

    /**
     * MCA Selector 的读写都基于真实 File（内部用 RandomAccessFile），
     * 而 SAF 的 content:// 拿不到 File。所以先落到自己的缓存目录，
     * 改完再写回原处。顺带给原文件留一份 .bak。
     */
    private fun prepare() {
        val cache = File(filesDir, "mca").apply { mkdirs() }
        val dst = File(cache, "work.mca")
        try {
            val bytes = if (srcPath.isNotBlank()) File(srcPath).readBytes()
            else contentResolver.openInputStream(srcUri!!)?.use { it.readBytes() }
            if (bytes == null) {
                post("读不了这个区域文件")
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
        val m = try {
            McaBridge.open(dst)
        } catch (t: Throwable) {
            Err.fail(t, "打开区域文件")
            post("打开失败：${t.message ?: t.javaClass.simpleName}\n${t.cause?.message ?: ""}")
            return
        }
        work = dst
        mca = m
        runOnUiThread {
            slot = -1
            layerMode = false
            render()
            image.setOnTouchListener { v, ev ->
                if (ev.action == android.view.MotionEvent.ACTION_UP) {
                    tapX = ev.x; tapY = ev.y
                    v.performClick()
                }
                true
            }
            image.setOnClickListener { onTapRegion(it) }
        }
    }

    private fun post(msg: String) = runOnUiThread {
        tvInfo.text = msg
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------ 渲染

    private fun render() {
        val m = mca ?: return
        val t0 = System.currentTimeMillis()
        if (slot < 0) {
            val px = IntArray(512 * 512)
            val n = McaBridge.drawRegion(m, 1, px)
            image.setImageBitmap(Bitmap.createBitmap(px, 512, 512, Bitmap.Config.ARGB_8888))
            val ms = System.currentTimeMillis() - t0
            tvInfo.text = "区域总览：$n 个区块　${ms}ms\n点一下想看的区块"
        } else {
            val c = m.getChunk(slot)
            if (c == null || c.isEmpty()) {
                tvInfo.text = "这个槽位是空的"
                return
            }
            val scale = 32
            val s = 16 * scale
            val px = IntArray(s * s)
            val ok = if (layerMode) {
                McaBridge.drawLayer(c, secY, scale, px) && !McaBridge.layerEmpty(px)
            } else {
                McaBridge.drawChunk(c, scale, px)
            }
            val ms = System.currentTimeMillis() - t0
            val dv = McaBridge.dataVersionOf(c)
            val loc = c.absoluteLocation
            if (ok) {
                image.setImageBitmap(Bitmap.createBitmap(px, s, s, Bitmap.Config.ARGB_8888))
            } else {
                image.setImageBitmap(null)
            }
            tvInfo.text = buildString {
                append("区块 (${loc.x}, ${loc.z})　DataVersion $dv\n")
                append(if (layerMode) "第 $secY 段（y≈${secY * 16}）" else "整列俯视")
                append("　${ms}ms")
                if (!ok && layerMode) append("\n这一段是空的")
            }
        }
    }

    private var tapX = 0f
    private var tapY = 0f

    private fun onTapRegion(v: android.view.View) {
        val m = mca ?: return
        if (slot >= 0) return
        val w = v.width.toFloat()
        val h = v.height.toFloat()
        // FIT_CENTER 下有留白，要按实际绘制区域换算
        val scale = minOf(w / 512f, h / 512f)
        val offX = (w - 512 * scale) / 2f
        val offY = (h - 512 * scale) / 2f
        val px = tapX - offX
        val py = tapY - offY
        if (px < 0 || py < 0) return
        val cx = (px / scale / 16).toInt()
        val cz = (py / scale / 16).toInt()
        if (cx !in 0..31 || cz !in 0..31) return
        val idx = cz * 32 + cx
        val c = m.getChunk(idx)
        if (c == null || c.isEmpty()) {
            Toast.makeText(this, "这个区块是空的", Toast.LENGTH_SHORT).show()
            return
        }
        slot = idx
        secY = SEC_MIN
        layerMode = false
        render()
    }

    private fun backToRegion() {
        slot = -1
        layerMode = false
        render()
    }

    private fun step(d: Int) {
        if (slot < 0) return
        layerMode = true
        var y = secY
        var found = false
        // 空段直接跳过去，不然得按 20 多次才看到东西
        repeat(SEC_MAX - SEC_MIN + 1) {
            y += d
            if (y < SEC_MIN) y = SEC_MAX
            if (y > SEC_MAX) y = SEC_MIN
            val c = mca?.getChunk(slot) ?: return@repeat
            val px = IntArray(16 * 16)
            if (McaBridge.drawLayer(c, y, 1, px) && !McaBridge.layerEmpty(px)) {
                secY = y
                found = true
                return@repeat
            }
        }
        if (!found) secY = y
        render()
    }

    // ------------------------------------------------------------------ 写入

    private fun askDelete() {
        if (slot < 0) {
            Toast.makeText(this, "先点一个区块", Toast.LENGTH_SHORT).show()
            return
        }
        val c = mca?.getChunk(slot) ?: return
        val loc = c.absoluteLocation
        MaterialAlertDialogBuilder(this)
            .setTitle("删除区块 (${loc.x}, ${loc.z})？")
            .setMessage(
                "这是**真删**：槽位表和方块数据一起抹掉。\n\n" +
                    "之后游戏再次走到这片区域会按当前版本重新生成地形，" +
                    "你在这里建过的东西不会回来。\n\n" +
                    "存档本体的 .bak 备份在打开时已经留好了。"
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton("删除") { _, _ ->
                McaBridge.deleteChunk(mca!!, slot)
                dirty = true
                slot = -1
                layerMode = false
                render()
                Toast.makeText(this, "已删除，记得点保存", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun save() {
        if (!dirty) {
            Toast.makeText(this, "没有改动", Toast.LENGTH_SHORT).show()
            return
        }
        val m = mca ?: return
        val dst = work ?: return
        Thread {
            val msg = try {
                McaBridge.save(m)
                val bytes = dst.readBytes()
                if (srcPath.isNotBlank()) {
                    File(srcPath).writeBytes(bytes)
                } else {
                    contentResolver.openOutputStream(srcUri!!, "wt")?.use { it.write(bytes) }
                        ?: throw IllegalStateException("打不开原文件写入")
                }
                dirty = false
                "已写回原文件"
            } catch (t: Throwable) {
                Err.fail(t, "写回区域文件")
                "写回失败：${t.message ?: t.javaClass.simpleName}"
            }
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }.start()
    }

    override fun onDestroy() {
        mca = null
        super.onDestroy()
    }
}
