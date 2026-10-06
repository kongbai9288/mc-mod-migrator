package com.kongbai.modmigrator

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.misode.mobile.GeneratorInfo
import io.misode.mobile.MisodeDataCache
import io.misode.mobile.MisodeEvent
import io.misode.mobile.MisodePaths
import io.misode.mobile.MisodeView

/**
 * 数据包 / 资源包生成器（基于 misode 的 137 个生成器）。
 *
 * # 和上一版自研表单的区别
 *
 * 上一版是内置 9 张表单，能写但种类太少。
 * 这里恢复完整的生成器清单，同时解决三个必须解决的问题：
 *
 * 1. **离线**：网页包只有代码，Minecraft 数据要另外取。
 *    由 [MisodeDataCache] 在 WebView 请求链路上拦截：
 *    命中缓存直接返回，没命中先下载再返回，之后不再联网。
 * 2. **中文**：网页默认写死英文，页面就绪后按系统 Locale 切一次；
 *    原生侧拿到的生成器标题也补译（bridge 里写死了英文词条）。
 * 3. **写对位置**：misode 只给内容，不给路径。
 *    这里按 [MisodePaths] 放到
 *    `datapacks/<包>/data/<命名空间>/<类型>/<名字>.json`，并补 `pack.mcmeta`。
 */
class MisodeActivity : AppCompatActivity() {

	private lateinit var misode: MisodeView
	private lateinit var tvStatus: TextView
	private var generators: List<GeneratorInfo> = emptyList()
	private var treeUri: Uri? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		title = "数据包生成器（全部 137 个）"

		MisodeDataCache.init(this)

		val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
		setContentView(root, ViewGroup.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

		val bar = LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			val p = (6 * resources.displayMetrics.density).toInt()
			setPadding(p, p, p, p)
		}
		bar.addView(smallBtn("生成器") { showGeneratorList() })
		bar.addView(smallBtn("版本") { showVersions() })
		bar.addView(smallBtn("中文") { misode.setLanguage("zh-cn"); toast("已切到中文") })
		bar.addView(smallBtn("离线数据") { showData() })
		bar.addView(smallBtn("存目录") { pickDir.launch(null) })
		bar.addView(smallBtn("写入") { save() })
		root.addView(bar, ViewGroup.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

		tvStatus = TextView(this).apply { textSize = 11f; text = "正在载入…" }
		root.addView(tvStatus, ViewGroup.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

		misode = MisodeView(this)
		misode.resourceInterceptor = { uri -> MisodeDataCache.resolve(uri) }
		misode.addListener { ev ->
			if (ev.type == MisodeEvent.READY) onReady()
			if (ev.type == MisodeEvent.OUTPUT) lastOutput = ev.value ?: ""
		}
		root.addView(misode, LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

		// 生成器结构表与 ID 表先后台取一次，页面要用时已经在本地
		Thread {
			MisodeDataCache.prefetchSchema()
			runOnUiThread { refreshStatus() }
		}.start()
	}

	private var lastOutput = ""

	private fun onReady() {
		misode.applySystemLanguage { lang ->
			runOnUiThread { tvStatus.text = "就绪（语言 $lang）\n${statusLine()}" }
		}
		misode.getGenerators { generators = it }
	}

	private fun statusLine(): String {
		val (n, size) = MisodeDataCache.stats()
		return "离线数据：已缓存 $n 个文件，共 ${MisodeDataCache.formatSize(size)}"
	}

	private fun refreshStatus() { tvStatus.text = statusLine() }

	/* ------------------------------------------------------------------ *
	 * 生成器列表（中文、可搜索）
	 * ------------------------------------------------------------------ */

	private fun showGeneratorList() {
		if (generators.isEmpty()) {
			misode.getGenerators { list ->
				generators = list
				if (list.isEmpty()) toast("还没拿到生成器清单") else showGeneratorList()
			}
			return
		}
		// 先问要不要搜索：MaterialAlertDialogBuilder 的 setItems 会覆盖 setView，
		// 把搜索框和列表塞进同一个弹窗，搜索框会被列表顶掉。
		MaterialAlertDialogBuilder(this)
			.setTitle("共 ${generators.size} 个生成器")
			.setItems(arrayOf("直接浏览全部", "按名字搜索")) { _, i ->
				if (i == 0) showGeneratorListFiltered("") else askQuery()
			}
			.setNegativeButton("关闭", null)
			.show()
	}

	private fun askQuery() {
		val et = EditText(this).apply {
			hint = "中文名或英文 id"
			setSingleLine(true)
		}
		val box = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			val p = (16 * resources.displayMetrics.density).toInt()
			setPadding(p, p / 2, p, 0)
			addView(et)
		}
		MaterialAlertDialogBuilder(this)
			.setTitle("搜索生成器")
			.setView(box)
			.setPositiveButton("搜索") { _, _ -> showGeneratorListFiltered(et.text.toString().trim()) }
			.setNegativeButton("取消", null)
			.show()
	}

	private fun showGeneratorListFiltered(query: String) {
		val shown = if (query.isEmpty()) generators else generators.filter {
			it.title.lowercase().contains(query.lowercase()) ||
				it.id.lowercase().contains(query.lowercase())
		}
		if (shown.isEmpty()) {
			toast("没有匹配的生成器")
			return
		}
		val names = shown.map { "${it.title}（${it.id}）" }.toTypedArray()
		MaterialAlertDialogBuilder(this)
			.setTitle("匹配 ${shown.size} 个")
			.setItems(names) { _, i -> misode.openGenerator(shown[i].id) }
			.setNegativeButton("返回", null)
			.show()
	}

	private fun showVersions() {
		misode.getVersions { vs ->
			if (vs.isEmpty()) { toast("还没拿到版本列表"); return@getVersions }
			MaterialAlertDialogBuilder(this)
				.setTitle("游戏版本")
				.setItems(vs.toTypedArray()) { _, i ->
					misode.setVersion(vs[i])
					toast("已切到 ${vs[i]}，首次使用会下载该版本的数据")
				}
				.show()
		}
	}

	/* ------------------------------------------------------------------ *
	 * 离线数据
	 * ------------------------------------------------------------------ */

	private fun showData() {
		val (n, size) = MisodeDataCache.stats()
		MaterialAlertDialogBuilder(this)
			.setTitle("离线数据")
			.setMessage(
				"已缓存 $n 个文件，共 ${MisodeDataCache.formatSize(size)}。\n\n" +
					"数据存在本应用私有目录，卸载会清掉。\n" +
					"用到哪个版本就下哪个（约 1 MB），下完之后完全离线可用。\n\n" +
					"全部 ${MisodeDataCache.VERSION_REFS.size} 个版本约十几 MB，可一次下完。"
			)
			.setPositiveButton("下载全部版本") { _, _ -> downloadAll() }
			.setNegativeButton("清空") { _, _ ->
				MisodeDataCache.clear()
				refreshStatus()
				toast("已清空")
			}
			.setNeutralButton("关闭", null)
			.show()
	}

	private fun downloadAll() {
		val tv = TextView(this).apply {
			text = "准备下载…"
			val p = (16 * resources.displayMetrics.density).toInt()
			setPadding(p, p, p, p)
		}
		val dlg = MaterialAlertDialogBuilder(this)
			.setTitle("下载全部版本数据")
			.setView(tv)
			.setNegativeButton("后台继续", null)
			.create()
		dlg.show()
		Thread {
			val refs = MisodeDataCache.VERSION_REFS
			var done = 0
			for (ref in refs) {
				MisodeDataCache.prefetchVersion(ref)
				done++
				runOnUiThread {
					tv.text = "已完成 $done / ${refs.size}（${ref}）\n${statusLine()}"
				}
			}
			runOnUiThread {
				tv.text = "全部完成\n${statusLine()}"
				refreshStatus()
			}
		}.start()
	}

	/* ------------------------------------------------------------------ *
	 * 写入存档
	 * ------------------------------------------------------------------ */

	private val pickDir = registerForActivityResult(
		ActivityResultContracts.OpenDocumentTree()
	) { uri ->
		if (uri == null) return@registerForActivityResult
		runCatching {
			contentResolver.takePersistableUriPermission(
				uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
			)
		}
		treeUri = uri
		toast("已选存档目录")
	}

	private fun save() {
		val uri = treeUri ?: run { toast("先点『存目录』选一个存档目录"); return }
		misode.getCurrentPath { path ->
			val url = path.trim('/')
			val gen = generators.firstOrNull { it.url == url || it.id == url.replace('-', '_') }
				?: generators.firstOrNull { it.id == url.replace('-', '_') }
			val target = gen?.let { MisodePaths.of(it.id) }
			if (gen == null || target == null || target.kind == MisodePaths.Kind.NONE) {
				toast("这个生成器不对应一个独立文件，用页面右上角的复制/下载按钮拿内容")
				return@getCurrentPath
			}
			misode.getOutput { out ->
				val content = out?.takeIf { it.isNotBlank() } ?: lastOutput
				if (content.isBlank()) {
					toast("还没有生成内容")
					return@getOutput
				}
				askAndWrite(uri, gen, target, content)
			}
		}
	}

	private fun askAndWrite(
		uri: Uri, gen: GeneratorInfo, target: MisodePaths.Target, content: String
	) {
		val etPack = EditText(this).apply { setText("mypack"); setSingleLine(true) }
		val etNs = EditText(this).apply { setText("mypack"); setSingleLine(true) }
		val etName = EditText(this).apply { setText(gen.id.replace('/', '_')); setSingleLine(true) }
		val etMc = EditText(this).apply { setText("1.21"); setSingleLine(true) }
		val box = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			val p = (16 * resources.displayMetrics.density).toInt()
			setPadding(p, p / 2, p, 0)
			addView(TextView(this@MisodeActivity).apply { text = "数据包名（文件夹名）" })
			addView(etPack)
			addView(TextView(this@MisodeActivity).apply { text = "命名空间（小写英文）" })
			addView(etNs)
			addView(TextView(this@MisodeActivity).apply { text = "文件名（不含扩展名）" })
			addView(etName)
			addView(TextView(this@MisodeActivity).apply { text = "游戏版本（决定 pack_format）" })
			addView(etMc)
			addView(TextView(this@MisodeActivity).apply {
				text = "将写入：datapacks/<包>/${target.dir}/<文件名>.json"
			})
		}
		MaterialAlertDialogBuilder(this)
			.setTitle("写入 ${gen.title}")
			.setView(box)
			.setPositiveButton("写入") { _, _ ->
				doWrite(
					uri,
					etPack.text.toString().trim().ifBlank { "mypack" },
					etNs.text.toString().trim().ifBlank { "mypack" }
						.lowercase().replace(Regex("[^a-z0-9_.-]"), "_"),
					etName.text.toString().trim().ifBlank { gen.id.replace('/', '_') },
					etMc.text.toString().trim(),
					target, gen, content
				)
			}
			.setNegativeButton("取消", null)
			.show()
	}

	private fun doWrite(
		uri: Uri, pack: String, ns: String, name: String, mcVersion: String,
		target: MisodePaths.Target, gen: GeneratorInfo, content: String
	) {
		try {
			val root = DocumentFile.fromTreeUri(this, uri)
				?: run { toast("打开不了这个目录"); return }

			val isAsset = target.kind == MisodePaths.Kind.ASSET
			// 资源包内容走 <存档>/resourcepacks，数据包走 datapacks
			val topName = if (isAsset) "resourcepacks" else "datapacks"
			val top = root.findFile(topName) ?: root.createDirectory(topName)
				?: run { toast("建不了 $topName 目录"); return }
			val packDir = top.findFile(pack) ?: top.createDirectory(pack)
				?: run { toast("建不了 $pack 目录"); return }

			if (packDir.findFile("pack.mcmeta") == null) {
				packDir.createFile("application/json", "pack.mcmeta")?.let { f ->
					contentResolver.openOutputStream(f.uri, "wt")?.use {
						it.write(mcmeta(pack, mcVersion, isAsset).toByteArray())
					}
				}
			}

			val base = packDir.findFile(if (isAsset) "assets" else "data")
				?: packDir.createDirectory(if (isAsset) "assets" else "data")
				?: run { toast("建不了内容目录"); return }

			var dir = base
			// 资源包固定 assets/minecraft/，数据包是 data/<命名空间>/
			for (part in (if (isAsset) "minecraft" else ns).split('/')) {
				if (part.isBlank()) continue
				dir = dir.findFile(part) ?: dir.createDirectory(part)
					?: run { toast("建不了 $part 目录"); return }
			}
			for (part in target.dir.split('/')) {
				if (part.isBlank()) continue
				dir = dir.findFile(part) ?: dir.createDirectory(part)
					?: run { toast("建不了 $part 目录"); return }
			}

			val fileName = if (gen.id == "sounds") "sounds.json" else "$name.json"
			val file = dir.findFile(fileName)
				?: dir.createFile("application/json", fileName)
				?: run { toast("建不了文件"); return }
			contentResolver.openOutputStream(file.uri, "wt")?.use {
				it.write(content.toByteArray())
			}
			val rel = "$topName/$pack/${if (isAsset) "assets/minecraft" else "data/$ns"}" +
				(if (target.dir.isBlank()) "" else "/${target.dir}") + "/$fileName"
			MaterialAlertDialogBuilder(this)
				.setTitle("已写入")
				.setMessage("$rel\n\n新存档要在创建世界时启用；老存档用 /datapack enable \"file/$pack\"。")
				.setPositiveButton("知道了", null)
				.show()
		} catch (t: Throwable) {
			Err.fail(t, "写生成器结果")
			toast("写入失败：${t.message ?: t.javaClass.simpleName}")
		}
	}

	private fun mcmeta(name: String, v: String, isAsset: Boolean): String {
		val m = Regex("""(\d+)\.(\d+)""").find(v)
		val fmt = if (isAsset) {
			// 资源包用 pack_format >= 22 的资源包序号，游戏按另一套识别
			if (m == null) 32 else {
				val major = m.groupValues[1].toIntOrNull() ?: 1
				val minor = m.groupValues[2].toIntOrNull() ?: 21
				when {
					major >= 2 -> 55
					minor >= 21 -> 32
					minor >= 20 -> 22
					else -> 13
				}
			}
		} else {
			if (m == null) 26 else {
				val major = m.groupValues[1].toIntOrNull() ?: 1
				val minor = m.groupValues[2].toIntOrNull() ?: 21
				when {
					major >= 2 -> 26
					minor >= 21 -> 34
					minor >= 20 -> 26
					minor >= 19 -> 10
					minor >= 18 -> 9
					else -> 26
				}
			}
		}
		return "{\n  \"pack\": {\n    \"pack_format\": $fmt,\n" +
			"    \"description\": \"${name.replace("\"", "")}\"\n  }\n}"
	}

	/* ------------------------------------------------------------------ */

	private fun smallBtn(t: String, fn: () -> Unit) = MaterialButton(this).apply {
		text = t
		minimumWidth = 0
		minWidth = 0
		setPadding(8, 0, 8, 0)
		textSize = 11f
		setOnClickListener { fn() }
	}

	private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

	override fun onBackPressed() {
		if (::misode.isInitialized && misode.goBack()) return
		super.onBackPressed()
	}

	override fun onDestroy() {
		if (::misode.isInitialized) misode.destroy()
		super.onDestroy()
	}

	companion object {
		fun open(ctx: Context) = ctx.startActivity(Intent(ctx, MisodeActivity::class.java))
	}
}
