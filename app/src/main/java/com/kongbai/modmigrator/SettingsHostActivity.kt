package com.kongbai.modmigrator

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment

/** 二级设置：入口页 + 各分组详情页 */
class SettingsHostActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LangPack.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemePrefs.styleRes(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings2)
        current = intent.getStringExtra("page") ?: "main"
        show(current, false)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    private var current: String = "main"

    fun show(page: String, addToStack: Boolean) {
        current = page
        val f: Fragment = when (page) {
            "backend" -> SettingsBackendFragment()
            "search" -> SettingsSearchFragment()
            "migrate" -> SettingsMigrateFragment()
            "storage" -> SettingsStorageFragment()
            "nav" -> SettingsNavFragment()
            "more" -> MoreFragment()
            "nav" -> SettingsNavFragment()
            "theme" -> SettingsThemeFragment()
            "lang" -> SettingsLangFragment()
            "plugin" -> PluginFragment()
            "devs" -> DevsFragment()
            "log" -> SettingsLogFragment()
            "about" -> SettingsAboutFragment()
            else -> SettingsMainFragment()
        }
        val t = supportFragmentManager.beginTransaction()
            .replace(R.id.container, f)
        if (addToStack) t.addToBackStack(page)
        t.commit()
        title = when (page) {
            "backend" -> getString(R.string.menu_backend)
            "search" -> getString(R.string.menu_search)
            "migrate" -> getString(R.string.menu_migrate)
            "storage" -> getString(R.string.menu_storage)
            "nav" -> getString(R.string.menu_nav)
            "more" -> getString(R.string.tab_more)
            "nav" -> getString(R.string.nav_title)
            "theme" -> getString(R.string.theme_title)
            "lang" -> getString(R.string.lang_title)
            "plugin" -> getString(R.string.menu_plugin)
            "devs" -> getString(R.string.menu_devs)
            "log" -> getString(R.string.menu_log)
            "about" -> getString(R.string.menu_about)
            else -> getString(R.string.tab_settings)
        }
    }

    /**
     * 返回键：二级页先退回设置入口页，入口页再按才退出回到主界面。
     * 之前是直接 finish，用户会莫名其妙跳到别的 tab。
     */
    override fun onBackPressed() {
        if (current != "main") {
            show("main", false)
            return
        }
        super.onBackPressed()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
