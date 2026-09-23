package com.kongbai.modmigrator

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment

/** 二级设置：入口页 + 各分组详情页 */
class SettingsHostActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
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
