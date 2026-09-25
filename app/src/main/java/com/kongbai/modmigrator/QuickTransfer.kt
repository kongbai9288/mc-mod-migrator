package com.kongbai.modmigrator

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 快速传输：把迁移包通过系统分享面板发出去。
 *
 * 不自己实现蓝牙/WiFi 直连协议——系统分享面板里已经包含了
 * 蓝牙、 nearby share、微信、QQ、网盘等所有通道，
 * 用户选哪个都行。自己造一套蓝牙传输既不稳定又要一堆权限。
 *
 * 蓝牙状态只做检测和引导（没开就提示去开），真正的发送交给系统。
 */
object QuickTransfer {

    /** 系统是否支持蓝牙（不一定代表已开启） */
    fun hasBluetooth(): Boolean {
        return try {
            BluetoothAdapter.getDefaultAdapter() != null
        } catch (t: Throwable) {
            false
        }
    }

    /** 蓝牙是否已开启 */
    fun bluetoothEnabled(): Boolean {
        return try {
            BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
        } catch (t: Throwable) {
            false
        }
    }

    /** 已配对设备名（用于界面展示，让用户知道有得传） */
    fun pairedDevices(ctx: Context): List<String> {
        return try {
            val a = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
            if (!a.isEnabled) return emptyList()
            val bonded: Set<BluetoothDevice> = a.bondedDevices ?: emptySet()
            bonded.mapNotNull { it.name }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /**
     * 打包并分享。
     * @param files 要打包的文件
     */
    /**
     * 打包并分享。
     *
     * **必须在后台线程调用**：打包是磁盘密集操作，
     * mods 目录动辄几百 MB，在主线程会 ANR。
     * 内部的 Toast 与 startActivity 会自动切回主线程。
     */
    fun shareFiles(ctx: Context, files: List<File>, zipName: String = "modmigrator-share.zip") {
        if (files.isEmpty()) {
            main(ctx) { Toast.makeText(ctx, "没有可发送的文件", Toast.LENGTH_SHORT).show() }
            return
        }
        var err = ""
        val zip = try {
            pack(ctx, files, zipName)
        } catch (e: Throwable) {
            err = e.message ?: "未知错误"
            null
        }
        if (zip == null) {
            main(ctx) { Toast.makeText(ctx, "打包失败：$err", Toast.LENGTH_SHORT).show() }
            return
        }
        // shareOne 里要 startActivity，必须回主线程
        main(ctx) { shareOne(ctx, zip, "迁移包") }
    }

    private fun main(ctx: Context, block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                block()
            } catch (t: Throwable) {
                Err.ignore(t, "分享回调")
            }
        }
    }

    /** 分享单个文件 */
    fun shareOne(ctx: Context, file: File, label: String = "文件") {
        try {
            val uri = FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", file
            )
            val i = Intent(Intent.ACTION_SEND).apply {
                type = mimeOf(file)
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, label)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(i, "发送到（蓝牙/附近分享/其他应用）")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(chooser)
        } catch (t: Throwable) {
            Toast.makeText(ctx, "无法分享：${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** 打成 zip */
    private fun pack(ctx: Context, files: List<File>, zipName: String): File? {
        val dir = File(ctx.cacheDir, "share").apply { if (!exists()) mkdirs() }
        val out = File(dir, zipName)
        if (out.exists()) out.delete()
        ZipOutputStream(out.outputStream()).use { zos ->
            for (f in files) {
                if (!f.exists() || !f.isFile) continue
                zos.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zos, 1 shl 16) }
                zos.closeEntry()
            }
        }
        return out
    }

    private fun mimeOf(f: File): String {
        val n = f.name.lowercase()
        return when {
            n.endsWith(".zip") -> "application/zip"
            n.endsWith(".jar") -> "application/java-archive"
            n.endsWith(".json") -> "application/json"
            n.endsWith(".txt") || n.endsWith(".log") -> "text/plain"
            else -> "*/*"
        }
    }

    /** 引导用户去开蓝牙（不申请权限，只跳设置页） */
    fun askEnableBluetooth(ctx: Context) {
        try {
            val i = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (t: Throwable) {
            Toast.makeText(ctx, "这台设备不支持蓝牙", Toast.LENGTH_SHORT).show()
        }
    }

    /** 状态描述，给设置页展示 */
    fun describe(ctx: Context): String {
        if (!hasBluetooth()) return "这台设备没有蓝牙，可以用系统分享里的其他通道（微信、网盘、附近分享）。"
        return if (bluetoothEnabled()) {
            val p = pairedDevices(ctx)
            "蓝牙已开启${if (p.isNotEmpty()) "，已配对：${p.take(3).joinToString("、")}" else "（暂无已配对设备）"}"
        } else {
            "蓝牙未开启。点「发送」后在系统面板里选蓝牙即可，或先在系统设置里打开蓝牙。"
        }
    }
}
