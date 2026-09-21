package com.kongbai.modmigrator

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object Prefs {

    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        get(ctx)
    }

    fun get(ctx: Context): SharedPreferences {
        if (prefs == null) {
            prefs = try {
                val key = MasterKey.Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    ctx,
                    "mm_secure",
                    key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (t: Throwable) {
                ctx.getSharedPreferences("mm_plain", Context.MODE_PRIVATE)
            }
        }
        return prefs!!
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
    const val PANEL_KEY = "panel_key"
    const val PANEL_DIR = "panel_dir"
    const val SCAN_ROOT = "scan_root"
}
