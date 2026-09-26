package com.kongbai.modmigrator

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object Prefs {

    // ⚠️ 必须 @Volatile + 双重检查。
    // get() 会被任意后台线程调用（下载、搜索、备份、WorkManager），
    // 原来的 `if (prefs == null) prefs = ...` 没有同步：
    // 两个线程同时首次调用会各自创建一个 EncryptedSharedPreferences 实例，
    // 之后读写的可能不是同一份 —— 配置改动丢失、设置看起来"没生效"。
    @Volatile
    private var prefs: SharedPreferences? = null
    private val lock = Any()

    @Volatile
    private var app: Context? = null

    fun init(ctx: Context) {
        app = ctx.applicationContext
        get(ctx)
    }

    /** 可能还没 init，返回 null 而不是崩 */
    fun appCtx(): Context? = app

    fun mirror(): Boolean {
        val c = app ?: return true
        return get(c).getBoolean(K.USE_MIRROR, true)
    }

    fun get(ctx: Context): SharedPreferences {
        // 双重检查锁定：第一次读 volatile 快路径，避免每次都进同步块
        return prefs ?: synchronized(lock) {
            prefs ?: build(ctx).also { prefs = it }
        }
    }

    private fun build(ctx: Context): SharedPreferences {
        return try {
            val mk = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                ctx,
                "mm_secure",
                mk,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (t: Throwable) {
            ctx.getSharedPreferences("mm_plain", Context.MODE_PRIVATE)
        }
    }

    /** 老代码里 get() 的兜底分支，现已并入 build()，保留注释说明 */

    @Volatile
    private var fallbackPrefs: SharedPreferences? = null

    private fun fallback(ctx: Context): SharedPreferences {
        fallbackPrefs?.let { return it }
        val p = try {
            ctx.getSharedPreferences("mm_fallback", Context.MODE_PRIVATE)
        } catch (t: Throwable) {
            // 连 SharedPreferences 都拿不到时，用一个纯内存实现兜底
            object : SharedPreferences {
                private val m = HashMap<String, Any?>()
                override fun getAll(): Map<String, *> = m
                override fun getString(k: String, d: String?): String? = m[k] as? String ?: d
                override fun getStringSet(k: String, d: MutableSet<String>?): MutableSet<String>? =
                    @Suppress("UNCHECKED_CAST") (m[k] as? MutableSet<String> ?: d)
                override fun getInt(k: String, d: Int): Int = m[k] as? Int ?: d
                override fun getLong(k: String, d: Long): Long = m[k] as? Long ?: d
                override fun getFloat(k: String, d: Float): Float = m[k] as? Float ?: d
                override fun getBoolean(k: String, d: Boolean): Boolean = m[k] as? Boolean ?: d
                override fun contains(k: String): Boolean = m.containsKey(k)
                override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
                    override fun putString(k: String, v: String?): SharedPreferences.Editor { m[k] = v; return this }
                    override fun putStringSet(k: String, v: MutableSet<String>?): SharedPreferences.Editor { m[k] = v; return this }
                    override fun putInt(k: String, v: Int): SharedPreferences.Editor { m[k] = v; return this }
                    override fun putLong(k: String, v: Long): SharedPreferences.Editor { m[k] = v; return this }
                    override fun putFloat(k: String, v: Float): SharedPreferences.Editor { m[k] = v; return this }
                    override fun putBoolean(k: String, v: Boolean): SharedPreferences.Editor { m[k] = v; return this }
                    override fun remove(k: String): SharedPreferences.Editor { m.remove(k); return this }
                    override fun clear(): SharedPreferences.Editor { m.clear(); return this }
                    override fun commit(): Boolean = true
                    override fun apply() {}
                }
                override fun registerOnSharedPreferenceChangeListener(
                    l: SharedPreferences.OnSharedPreferenceChangeListener?
                ) {}
                override fun unregisterOnSharedPreferenceChangeListener(
                    l: SharedPreferences.OnSharedPreferenceChangeListener?
                ) {}
            }
        }
        fallbackPrefs = p
        return p
    }
}

object K {
    const val TOKEN = "gh_token"
    const val OWNER = "gh_owner"
    const val REPO = "gh_repo"
    const val BRANCH = "gh_branch"
    const val DEVICE_ID = "device_id"
    const val DEVICE_LABEL = "device_label"
    const val SRC_URI = "src_uri"
    const val DST_URI = "dst_uri"
    const val SOURCE = "pref_source"
    const val CF_KEY = "cf_key"
    const val DEF_VERSION = "def_version"
    const val DEF_LOADER = "def_loader"
    const val AUTO_INSTALL = "auto_install"
    const val AUTO_LAUNCH = "auto_launch"
    const val AUTO_SYNC = "auto_sync"
    const val LAUNCHER = "launcher_pkg"
    const val PANEL_BASE = "panel_base"
    const val NAV_BOTTOM = "nav_bottom"
    const val PANEL_KEY = "panel_key"
    const val NAV_KEYS = "nav_keys"
    const val THEME = "theme_index"
    const val LANG_CODE = "lang_code"
    const val LANG_PACK = "lang_pack"
    const val PANEL_USER = "panel_user"
    const val PANEL_PASS = "panel_pass"
    const val PANEL_MODE_KEY = "panel_mode_key"
    const val PANEL_SERVER_ID = "panel_server_id"
    const val PANEL_OUT_DIR = "panel_out_dir"  // 服务器更新结果的本地存放目录
    const val PANEL_DIR = "panel_dir"
    const val SCAN_ROOT = "scan_root"
    const val USE_MIRROR = "use_mirror"
    const val AUTO_TRANS = "auto_trans"
    const val BACKEND_BASE = "backend_base"
    const val BACKEND_BACKUP = "backend_backup"
    const val OFFLINE = "offline_mode"
    const val USE_BACKEND = "use_backend"
    const val USE_OFFICIAL_CF = "use_official_cf"
    const val WORKDIR_URI = "workdir_uri"
    const val AGG_SEARCH = "agg_search"
    const val RECOMMEND = "recommend"
    const val LOCAL_ICON = "local_icon"
    const val AUTO_BACKUP_HOURS = "auto_backup_hours"
    const val CLOUD_UPLOAD_URL = "cloud_upload_url"
    const val ANIM_MODE = "anim_mode"
    const val DOWNLOAD_PARALLEL = "download_parallel"
    const val UPDATE_CHECK = "update_check"
    const val LAST_UPDATE_TAG = "last_update_tag"
    const val LAST_UPDATE_NOTES = "last_update_notes"
    const val LAST_UPDATE_AT = "last_update_at"
    const val LAST_UPDATE_TIP = "last_update_tip"
    const val FAVORITES = "favorites"
    const val GH_TOKEN_BACKEND = "gh_token_backend"

    // ---- 本轮新增 ----
    const val NIGHT_MODE = "night_mode"              // 深色模式：0跟随系统 1浅色 2深色
    const val AUTO_TRANS_PAGE = "auto_trans_page"    // 打开网页自动翻译
    const val TRASH_DAYS = "trash_days"              // 回收站保留天数
    const val TRAFFIC_WARN_MB = "traffic_warn_mb"    // 流量提醒阈值 MB
    const val BACKUP_ONLY_WIFI = "backup_only_wifi"
    const val EXPERIMENTAL_UPGRADE = "exp_upgrade"   // 实验性自动升级（彩蛋激活）
    const val ANNO_CLOSED = "anno_closed"            // 已关闭的公告版本号
    const val ANNO_CLEARED = "anno_cleared"
    const val FIRST_MARKET_VISIT = "first_market_visit"
    const val GH_AVATAR = "gh_avatar"
    const val GH_LOGIN = "gh_login"
    const val BROWSER_INTERNAL = "browser_internal"  // 一律用内置浏览器
    const val RESUME_DOWNLOAD = "resume_download"    // 断点续传
    const val ANIM_SPEED = "anim_speed"
    const val LAST_VIEW_KEY = "last_view_key"
    const val LAST_VIEW_TITLE = "last_view_title"
    const val LAST_VIEW_AT = "last_view_at"              // 动画速率：0慢 1正常 2快
    const val MC_VERSION = "mc_version"            // 目标 MC 版本
    const val LOADER = "loader_type"                // 目标加载器 fabric/forge/quilt/neoforge
    const val DOWNLOAD_SPEED = "download_speed"      // 下载并发：0单线程 1适中 2最大

}
