package com.kongbai.modmigrator

import android.content.Context
import android.util.Base64
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class Creds(val owner: String, val repo: String, val branch: String, val token: String)

object SyncManager {

    private const val ROOT = "modmigrator/devices"

    fun creds(ctx: Context): Creds? {
        val p = Prefs.get(ctx)
        val token = p.getString(K.TOKEN, "") ?: ""
        var owner = p.getString(K.OWNER, "") ?: ""
        val repo = p.getString(K.REPO, "") ?: ""
        val branch = (p.getString(K.BRANCH, "") ?: "").ifBlank { "main" }
        if (token.isBlank() || repo.isBlank()) return null
        if (owner.isBlank()) owner = "kongbai9288"
        return Creds(owner, repo, branch, token)
    }

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    fun upload(ctx: Context, log: ((String) -> Unit)? = null): String {
        val c = creds(ctx) ?: return "未配置 Token 或仓库名（设置 → GitHub 私有仓库）"
        val p = Prefs.get(ctx)
        val id = Store.deviceId(ctx)
        val label = Store.deviceLabel(ctx)
        val src = Fs.tree(ctx, p.getString(K.SRC_URI, null))
        val mc = p.getString(K.DEF_VERSION, "") ?: ""
        val loader = (p.getString(K.DEF_LOADER, "") ?: "").ifBlank { "auto" }

        val mods = JSONArray()
        val modDir = if (src == null) null else Fs.find(src, "mods")
        if (modDir != null) {
            // ── 先本地算完所有指纹，再一次性批量反查 ──────────────
            // 之前是在循环里逐个调 lookupHashSimple：
            // 50 个模组 = 50 次串行网络请求，同步一次要等好几分钟，
            // 期间界面像卡死。这和迁移页之前踩的是同一个坑。
            val jars = Fs.children(modDir).filter {
                it.isFile && (it.name ?: "").endsWith(".jar", true)
            }
            val entries = ArrayList<Pair<String, String>>()   // (文件名, sha1)
            for (f in jars) {
                val n = f.name ?: continue
                val sha1 = Fs.sha1(ctx, f)
                if (sha1.isBlank()) {
                    // 读不出来的也要记进清单，只是没有 projectId
                    entries.add(n to "")
                    continue
                }
                entries.add(n to sha1)
            }
            val hashes = entries.map { it.second.lowercase() }
            val found = ModrinthApi.lookupHashes(hashes)
            ModrinthApi.refreshAll(found.values.map { it.projectId })

            for ((n, sha1) in entries) {
                val info = if (sha1.isBlank()) null else found[sha1.lowercase()]
                val o = JSONObject()
                o.put("file", n)
                o.put("sha1", sha1)
                o.put("projectId", info?.projectId ?: "")
                o.put("slug", if (info == null) "" else ModrinthApi.slug(info.projectId))
                o.put(
                    "name",
                    if (info == null) n
                    else ModrinthApi.title(info.projectId).ifBlank { n }
                )
                mods.put(o)
            }
        }

        val man = JSONObject()
        man.put("device", id)
        man.put("label", label)
        man.put("time", now())
        man.put("mcVersion", mc)
        man.put("loader", loader)
        man.put("mods", mods)

        var msg = if (GitHubApi.putText(
                c.owner, c.repo, "$ROOT/$id/manifest.json", c.branch, c.token,
                man.toString(2), "sync manifest from $label"
            )
        ) "清单已上传（${mods.length()} 个模组）" else "清单上传失败"

        if (src != null) {
            val zip = File(ctx.cacheDir, "bundle.zip")
            val names = listOf("config", "scripts", "resourcepacks", "shaderpacks")
            val okZip = BundleManager.zipInto(ctx, src, names, zip) { log?.invoke(it) }
            if (okZip && zip.exists() && zip.length() > 0) {
                val mb = zip.length() / 1024 / 1024
                if (zip.length() > 25L * 1024 * 1024) {
                    msg += "；配置包 ${mb}MB，偏大，GitHub 单文件上限 100MB"
                }
                val b64 = Base64.encodeToString(zip.readBytes(), Base64.NO_WRAP)
                val sha = GitHubApi.sha(c.owner, c.repo, "$ROOT/$id/config.zip", c.branch, c.token)
                msg += if (GitHubApi.putBase64(
                        c.owner, c.repo, "$ROOT/$id/config.zip", c.branch, c.token,
                        b64, "sync config from $label", sha
                    )
                ) {
                    "；配置包已上传"
                } else {
                    // 把具体原因带给用户，而不是一句没头没脑的"上传失败"
                    val why = GitHubApi.lastError
                    "；配置包上传失败" + if (why.isNotBlank()) "：$why" else ""
                }
            } else {
                msg += "；无可打包的配置"
            }
        } else {
            msg += "；未设置「迁移前」的目录，只上传清单"
        }
        return msg
    }

    fun devices(ctx: Context): List<RemoteDevice> {
        val c = creds(ctx) ?: return emptyList()
        val ids = try {
            GitHubApi.listDir(c.owner, c.repo, ROOT, c.branch, c.token)
        } catch (t: Throwable) {
            emptyList<String>()
        }
        val out = mutableListOf<RemoteDevice>()
        for (id in ids) {
            val txt = GitHubApi.getText(c.owner, c.repo, "$ROOT/$id/manifest.json", c.branch, c.token)
                ?: continue
            try {
                val o = JSONObject(txt)
                val arr = o.optJSONArray("mods")
                out.add(
                    RemoteDevice(
                        id = id,
                        label = o.optString("label", id),
                        time = o.optString("time", ""),
                        mcVersion = o.optString("mcVersion", ""),
                        loader = o.optString("loader", ""),
                        modCount = arr?.length() ?: 0,
                        manifestPath = "$ROOT/$id/manifest.json"
                    )
                )
            } catch (t: Throwable) {
                // skip broken manifest
                     Err.ignore(t, "skip broken manifest")
                 }
        }
        return out.sortedByDescending { it.time }
    }

    fun restore(ctx: Context, d: RemoteDevice, includeMods: Boolean, log: (String) -> Unit) {
        val c = creds(ctx) ?: throw RuntimeException("未配置 Token 或仓库名")
        val dst = Targets.root(ctx) ?: throw RuntimeException("「迁移后」的目录不可用")
        val bytes = GitHubApi.getBytes(c.owner, c.repo, "$ROOT/${d.id}/config.zip", c.branch, c.token)
        if (bytes != null) {
            val f = File(ctx.cacheDir, "restore_${d.id}.zip")
            f.writeBytes(bytes)
            log("已下载配置包 ${bytes.size / 1024}KB")
            BundleManager.unzip(ctx, f, dst, log)
        } else {
            log("该设备没有配置包")
        }

        if (includeMods) {
            val txt = GitHubApi.getText(c.owner, c.repo, d.manifestPath, c.branch, c.token)
            if (txt != null) {
                val o = JSONObject(txt)
                val mc = o.optString("mcVersion", "")
                val loader = o.optString("loader", "auto").ifBlank { "auto" }
                val arr = o.optJSONArray("mods") ?: JSONArray()
                val dir = Fs.ensureDir(dst, "mods")
                // ensureDir 失败返回 null 时不能继续往下走，
                // 否则 Downloader.download 收到 null 目录会失败，
                // 却照样计入"成功"（见 BatchModOps 的计数修正）。
                if (dir == null) {
                    log("目标 mods 目录不可用，已中止本次恢复")
                    return
                }
                // ── 同样改批量 ──────────────────────────────────
                // 之前每个可识别模组调一次 /project/{id}/version：
                // 恢复 30 个模组就是 30 次串行请求，界面像卡死。
                // /version_files/update 一次就能拿到全部的最新兼容版本，
                // 而且它按**哈希**匹配，比按 projectId 查更准
                // （能区分同一个项目下的不同文件）。
                val pids = ArrayList<String>()
                val metas = ArrayList<Pair<String, String>>()   // (sha1, 显示名)
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    val pid = m.optString("projectId", "")
                    if (pid.isBlank()) {
                        log("跳过（无法识别）：${m.optString("file")}")
                        continue
                    }
                    pids.add(pid)
                    metas.add(
                        m.optString("sha1", "") to
                            m.optString("name", m.optString("file"))
                    )
                }
                ModrinthApi.refreshAll(pids)
                val allHashes = metas.map { it.first }.filter { it.isNotBlank() }
                val latest = if (allHashes.isEmpty()) {
                    emptyMap<String, ModFile>()
                } else {
                    ModrinthApi.latestForHashes(allHashes, mc, loader)
                }

                for ((sha1, display) in metas) {
                    val f = if (sha1.isBlank()) null else latest[sha1.lowercase()]
                    if (f == null) {
                        log("无可用版本：$display")
                        continue
                    }
                    val name = f.fileName.ifBlank { Downloader.guessName(f.url) }
                    val out = Downloader.download(ctx, f.url, dir, name)
                    log(if (out == null) "下载失败：$name" else "已下载：$name")
                }
            }
        }
    }

    fun schedule(ctx: Context) {
        try {
            // WorkManager 没初始化好时直接跳过，不能拖垮调用方
            val on = Prefs.get(ctx).getBoolean(K.AUTO_SYNC, false)
            val wm = WorkManager.getInstance(ctx)
            if (!on) {
                wm.cancelUniqueWork("mm_sync")
                return
            }
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
            wm.enqueueUniquePeriodicWork("mm_sync", ExistingPeriodicWorkPolicy.UPDATE, req)
        } catch (t: Throwable) {
            // ignore
                 Err.ignore(t, "ignore")
             }
    }
}
