package com.kongbai.modmigrator

import android.content.Context
import android.widget.ImageView
import java.util.Locale

/**
 * 加载器图标与远程图标兼容。
 *
 * 两件事：
 *
 * 一、**加载器图标**：Fabric / Forge / NeoForge / Quilt / OptiFine 各有图标，
 *     列表里显示出来比清一色灰方块直观得多，也更好认。
 *     图标是本应用自己按各加载器主色绘制的矢量图（不涉及第三方素材版权），
 *     只取"铁砧 / 织物 / 拼布 / 镜头"这类通用造型。
 *
 * 二、**远程图标 WebP 兼容**：Modrinth 的图标大量是 WebP 格式。
 *     直接交给部分图片库会解码失败，于是只剩首字母占位头像——
 *     这正是"远程图标只显示首字母"的原因。
 *     这里统一走 Coil（内置 WebP 解码），并对 SVG 也做兼容。
 */
object LoaderIcons {

    /** 加载器 → 图标资源 */
    fun res(loader: String): Int {
        return when (loader.lowercase(Locale.ROOT)) {
            "fabric" -> R.drawable.ic_loader_fabric
            "forge" -> R.drawable.ic_loader_forge
            "neoforge" -> R.drawable.ic_loader_neoforge
            "quilt" -> R.drawable.ic_loader_quilt
            "optifine" -> R.drawable.ic_loader_optifine
            else -> R.drawable.ic_loader_unknown
        }
    }

    /** 加载器显示名（中文） */
    fun label(loader: String): String {
        return when (loader.lowercase(Locale.ROOT)) {
            "fabric" -> "Fabric"
            "forge" -> "Forge"
            "neoforge" -> "NeoForge"
            "quilt" -> "Quilt"
            "optifine" -> "OptiFine"
            else -> "未知"
        }
    }

    /**
     * 加载远程图标（含 WebP / SVG）。
     * 失败时保留传入的占位图，不显示空白。
     */
    fun loadRemote(
        ctx: Context,
        iv: ImageView,
        url: String,
        placeholder: android.graphics.drawable.Drawable? = null
    ) {
        if (url.isBlank()) {
            placeholder?.let { iv.setImageDrawable(it) }
            return
        }
        try {
            // 用带 SVG / GIF 解码器的 ImageLoader：
            // Modrinth 的图标有 WebP 也有 SVG，默认 loader 遇到 SVG 会直接失败，
            // 结果就是只能显示首字母占位图。
            imageLoader(ctx).enqueue(
                coil.request.ImageRequest.Builder(ctx)
                    .data(url)
                    .target(iv)
                    .crossfade(true)
                    .apply {
                        if (placeholder != null) {
                            placeholder(placeholder)
                            error(placeholder)
                        }
                    }
                    .build()
            )
        } catch (t: Throwable) {
            placeholder?.let { iv.setImageDrawable(it) }
        }
    }

    /** 全局共用的 ImageLoader：注册了 SVG 与 GIF 解码器 */
    @Volatile
    private var loader: coil.ImageLoader? = null

    private fun imageLoader(ctx: Context): coil.ImageLoader {
        loader?.let { return it }
        val l = coil.ImageLoader.Builder(ctx)
            .components {
                add(coil.decode.SvgDecoder.Factory())
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    add(coil.decode.ImageDecoderDecoder.Factory())
                } else {
                    add(coil.decode.GifDecoder.Factory())
                }
            }
            .crossfade(true)
            .build()
        loader = l
        return l
    }

    /** 判断某地址是不是 WebP（用于日志与提示） */
    fun isWebp(url: String): Boolean =
        url.substringBefore('?').endsWith(".webp", true)

    /**
     * OptiFine 检测：看 jar 里有没有 OptiFine 的特征类。
     * OptiFine 不是标准 Forge/Fabric 模组，元数据文件里查不到，
     * 只能靠特征类识别。
     */
    fun detectOptiFine(ctx: Context, uri: android.net.Uri): Boolean {
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                java.util.zip.ZipInputStream(input).use { zip ->
                    var e = zip.nextEntry
                    var n = 0
                    while (e != null && n < 300) {
                        n++
                        val name = e.name ?: ""
                        if (name.startsWith("optifine/", true) ||
                            name.startsWith("net/optifine/", true)
                        ) return true
                        e = zip.nextEntry
                    }
                }
            }
            false
        } catch (t: Throwable) {
            false
        }
    }
}
