package com.kongbai.modmigrator

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

/**
 * 加载器下拉：每项前面带加载器图标。
 *
 * 之前用 `android:entries="@array/loaders"`，系统生成的是纯文字 ArrayAdapter，
 * 五项全都是白底黑字，得逐条读文字才能分清哪个是 NeoForge 哪个是 Forge。
 * 加了品牌图标后一眼就能认出来。
 *
 * **值仍然是标准名**（auto / fabric / forge …），只有显示层是 label + 图标 ——
 * 这样所有既有的 `spinner.selectedItem.toString()` 读法都不用改。
 */
object LoaderSpinner {

    /**
     * 给 Spinner 装上带图标的适配器。
     * @param values 标准名列表，通常来自 [Loaders.visible]
     */
    fun attach(sp: Spinner, values: List<String>) {
        val ctx = sp.context
        sp.adapter = Adapter(ctx, values)
    }

    /**
     * 按当前设置装载：用户在设置里勾了「显示手机不支持的加载器」就全列，
     * 否则只列手机跑得起来的那几个。
     */
    fun attachByPref(sp: Spinner) {
        val show = Prefs.get(sp.context).getBoolean(K.SHOW_EXTRA_LOADERS, false)
        attach(sp, Loaders.visible(show))
    }

    /** 按标准名选中；找不到就保持原样（不越界） */
    fun select(sp: Spinner, value: String) {
        val a = sp.adapter as? Adapter ?: return
        val i = a.values.indexOf(Loaders.normalize(value))
        if (i >= 0) sp.setSelection(i)
    }

    /** 取当前选中项的标准名 */
    fun value(sp: Spinner): String {
        val a = sp.adapter as? Adapter ?: return "auto"
        val i = sp.selectedItemPosition
        return if (i in a.values.indices) a.values[i] else "auto"
    }

    private class Adapter(
        private val ctx: Context,
        val values: List<String>
    ) : BaseAdapter() {

        override fun getCount(): Int = values.size
        override fun getItem(pos: Int): String = values[pos]
        override fun getItemId(pos: Int): Long = pos.toLong()

        override fun getView(pos: Int, cv: View?, parent: ViewGroup?): View =
            row(pos, cv, false)

        override fun getDropDownView(pos: Int, cv: View?, parent: ViewGroup?): View =
            row(pos, cv, true)

        private fun row(pos: Int, cv: View?, dropdown: Boolean): View {
            @Suppress("UNCHECKED_CAST")
            val holder: Holder
            val v: View
            if (cv is LinearLayout && cv.tag is Holder) {
                v = cv
                holder = cv.tag as Holder
            } else {
                val d = ctx.resources.displayMetrics.density
                val root = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val padV = (d * (if (dropdown) 10 else 6)).toInt()
                    setPadding((d * 8).toInt(), padV, (d * 8).toInt(), padV)
                }
                val icon = ImageView(ctx).apply {
                    val s = (d * 20).toInt()
                    layoutParams = LinearLayout.LayoutParams(s, s).apply {
                        marginEnd = (d * 8).toInt()
                    }
                }
                val text = TextView(ctx).apply {
                    textSize = 14f
                    setTextColor(currentTextColor(ctx))
                }
                root.addView(icon)
                root.addView(text)
                holder = Holder(icon, text)
                root.tag = holder
                v = root
            }

            val ld = values[pos]
            holder.icon.setImageResource(Loaders.icon(ld))
            // 图标是纯黑线稿，染成文字色才能在深浅两套主题下都看得清
            holder.icon.setColorFilter(currentTextColor(ctx))
            val label = Loaders.label(ld)
            holder.text.text =
                if (dropdown && !Loaders.isPhoneSupported(ld)) "$label（手机通常不支持）"
                else label
            return v
        }

        private class Holder(val icon: ImageView, val text: TextView)

        /**
         * 当前主题的正文色。
         * 取不到就退回灰/白——图标是黑色线稿，底色深时也会看不见。
         */
        private fun currentTextColor(ctx: Context): Int {
            return try {
                val tv = android.util.TypedValue()
                val ok = ctx.theme.resolveAttribute(
                    android.R.attr.textColorPrimary, tv, true
                )
                if (ok && tv.resourceId != 0) {
                    if (android.os.Build.VERSION.SDK_INT >= 23) ctx.getColor(tv.resourceId)
                    else @Suppress("DEPRECATION") ctx.resources.getColor(tv.resourceId)
                } else Color.GRAY
            } catch (t: Throwable) {
                Color.GRAY
            }
        }
    }
}
