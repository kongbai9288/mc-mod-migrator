package com.opentransfer.openlist

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import openlistbridge.Openlistbridge
import org.json.JSONObject
import java.io.File

/**
 * Base implementation of CloudDrive using OpenList bridge.
 * 
 * 原版 OpenList Bridge API 支持的方法：
 * - create(driverType, configJson) → handle
 * - list(handle, path) → List<CloudFile>
 * - getDownloadURL(handle, path) → DownloadInfo
 * - upload(handle, parentPath, fileName, localFilePath, mimeType) → CloudFile
 * - mkdir(handle, parentPath, dirName) → CloudFile
 * - delete(handle, path) → void
 * - rename(handle, path, newName) → CloudFile
 * - move(handle, srcPath, dstDirPath) → CloudFile
 * - copy(handle, srcPath, dstDirPath) → CloudFile
 * - destroy(handle) → void
 */
/**
 * 基于原版 OpenList gomobile bridge 的实现。
 * 
 * Go bridge 导出的函数（openlistbridge 包）：
 * - Create(driverType, configJSON) → JSON
 * - List(handle, path) → JSON
 * - GetDownloadURL(handle, path) → JSON
 * - Upload(handle, parentPath, fileName, localFilePath, mimeType) → JSON
 * - Mkdir(handle, parentPath, dirName) → JSON
 * - Delete(handle, path) → JSON
 * - Rename(handle, path, newName) → JSON
 * - Move(handle, srcPath, dstDirPath) → JSON
 * - Copy(handle, srcPath, dstDirPath) → JSON
 * - Destroy(handle) → JSON
 * 
 * 所有方法返回 JSON：{"success":true/false, "data":..., "error":"..."}
 */
internal class OpenListDrive(
    private val provider: String,
    private val configJson: String,
) : CloudDrive {
    
    private var handle: String? = null
    
    private suspend fun ensureHandle(): String = withContext(Dispatchers.IO) {
        if (handle == null) {
            val result = Openlistbridge.create(provider, configJson)
            val parsed = JSONObject(result)
            if (!parsed.optBoolean("success", false)) {
                throw OpenListException(parsed.optString("error", "create failed"))
            }
            handle = parsed.getJSONObject("data").getString("handle")
        }
        handle!!
    }
    
    private fun checkSuccess(json: String, operation: String): JSONObject {
        val parsed = JSONObject(json)
        if (!parsed.optBoolean("success", false)) {
            throw OpenListException("$operation failed: ${parsed.optString("error", "unknown error")}")
        }
        return parsed
    }
    
    private fun parseCloudFile(json: JSONObject): RemoteFile = RemoteFile(
        path = json.optString("path", ""),
        size = json.optLong("size", 0),
        modifiedAt = json.optLong("modified_at", 0),
        isDirectory = json.optBoolean("is_dir", false),
    )
    
    override suspend fun testConnection(): OpenListResult<Unit> = withContext(Dispatchers.IO) {
        try {
            ensureHandle()
            OpenListResult.success(Unit)
        } catch (e: Exception) {
            OpenListResult.failure(e)
        }
    }
    
    override suspend fun list(path: String): OpenListResult<List<RemoteFile>> = withContext(Dispatchers.IO) {
        try {
            val h = ensureHandle()
            val result = Openlistbridge.list(h, path)
            val parsed = checkSuccess(result, "list")
            val arr = parsed.optJSONArray("data")
            val files = mutableListOf<RemoteFile>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    files.add(parseCloudFile(arr.getJSONObject(i)))
                }
            }
            OpenListResult.success(files)
        } catch (e: Exception) {
            OpenListResult.failure(e)
        }
    }
    
    /** OpenList bridge 无 Get 方法，用 list 父目录后按 name 筛选 */
    override suspend fun stat(path: String): OpenListResult<RemoteFile> = withContext(Dispatchers.IO) {
        try {
            val h = ensureHandle()
            val parentPath = path.substringBeforeLast("/", "").ifBlank { "/" }
            val fileName = path.substringAfterLast("/", path)
            val result = Openlistbridge.list(h, parentPath)
            val parsed = checkSuccess(result, "stat")
            val arr = parsed.optJSONArray("data")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    if (obj.optString("name") == fileName) {
                        return@withContext OpenListResult.success(parseCloudFile(obj))
                    }
                }
            }
            OpenListResult.failure(OpenListException("file not found: $path"))
        } catch (e: Exception) {
            OpenListResult.failure(e)
        }
    }
    
    override suspend fun upload(
        local: File,
        remotePath: String,
        onProgress: ((Long) -> Unit)?,
    ): OpenListResult<RemoteFile> = withContext(Dispatchers.IO) {
        try {
            val h = ensureHandle()
            val parentPath = remotePath.substringBeforeLast("/", "").ifBlank { "/" }
            val fileName = remotePath.substringAfterLast("/", remotePath)
            onProgress?.invoke(0)
            val result = Openlistbridge.upload(h, parentPath, fileName, local.absolutePath, "application/octet-stream")
            val parsed = checkSuccess(result, "upload")
            onProgress?.invoke(local.length())
            OpenListResult.success(parseCloudFile(parsed.getJSONObject("data")))
        } catch (e: Exception) {
            OpenListResult.failure(e)
        }
    }
    
    /** 通过 getDownloadURL 获取临时下载链接，然后用 OkHttp 流式下载 */
    override suspend fun download(
        remotePath: String,
        local: File,
        onProgress: ((Long) -> Unit)?,
    ): OpenListResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val h = ensureHandle()
            val result = Openlistbridge.getDownloadURL(h, remotePath)
            val parsed = checkSuccess(result, "getDownloadURL")
            val url = parsed.getJSONObject("data").getString("url")
            onProgress?.invoke(0)

            val request = okhttp3.Request.Builder().url(url).get().build()
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code !in 200..299) {
                    throw OpenListException("download failed: ${response.code}")
                }
                val body = response.body ?: throw OpenListException("empty response body")
                val contentLength = body.contentLength()
                var bytesRead = 0L
                local.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            bytesRead += read
                            onProgress?.invoke(
                                if (contentLength > 0) bytesRead * 100 / contentLength else bytesRead
                            )
                        }
                    }
                }
            }
            OpenListResult.success(Unit)
        } catch (e: Exception) {
            OpenListResult.failure(e)
        }
    }
    
    override suspend fun delete(path: String): OpenListResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val h = ensureHandle()
            Openlistbridge.delete(h, path)
            OpenListResult.success(Unit)
        } catch (e: Exception) {
            OpenListResult.failure(e)
        }
    }
    
    /** OpenList bridge Mkdir(handle, parentPath, dirName) */
    override suspend fun mkdir(path: String): OpenListResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val h = ensureHandle()
            val parentPath = path.substringBeforeLast("/", "").ifBlank { "/" }
            val dirName = path.substringAfterLast("/", path)
            Openlistbridge.mkdir(h, parentPath, dirName)
            OpenListResult.success(Unit)
        } catch (e: Exception) {
            OpenListResult.failure(e)
        }
    }
    
    /** 逐级创建目录 */
    override suspend fun ensureDirectory(path: String): OpenListResult<Unit> = withContext(Dispatchers.IO) {
        try {
            var current = "/"
            for (part in path.trim('/').split("/").filter { it.isNotBlank() }) {
                val child = current.trimEnd('/') + "/" + part
                val exists = list(current).getOrNull()?.any { it.name == part } ?: false
                if (!exists) {
                    mkdir(current).getOrThrow()
                }
                current = child
            }
            OpenListResult.success(Unit)
        } catch (e: Exception) {
            OpenListResult.failure(e)
        }
    }
    
    /** 用 stat 判断文件是否存在 */
    override suspend fun exists(path: String): OpenListResult<Boolean> = withContext(Dispatchers.IO) {
        try {
            OpenListResult.success(stat(path).isSuccess)
        } catch (e: Exception) {
            OpenListResult.success(false)
        }
    }
    
    /** OpenList bridge Rename(handle, path, newName) — newName 只传文件名 */
    override suspend fun rename(oldPath: String, newPath: String): OpenListResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val h = ensureHandle()
            val newName = newPath.substringAfterLast("/", newPath)
            Openlistbridge.rename(h, oldPath, newName)
            OpenListResult.success(Unit)
        } catch (e: Exception) {
            OpenListResult.failure(e)
        }
    }
    
    /** OpenList bridge Copy(handle, srcPath, dstDirPath) — dst 是目标目录路径 */
    override suspend fun copy(source: String, destination: String): OpenListResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val h = ensureHandle()
            Openlistbridge.copy(h, source, destination)
            OpenListResult.success(Unit)
        } catch (e: Exception) {
            OpenListResult.failure(e)
        }
    }
    
    override suspend fun getDownloadUrl(path: String): OpenListResult<String> = withContext(Dispatchers.IO) {
        try {
            val h = ensureHandle()
            val result = Openlistbridge.getDownloadURL(h, path)
            val parsed = checkSuccess(result, "getDownloadURL")
            OpenListResult.success(parsed.getJSONObject("data").getString("url"))
        } catch (e: Exception) {
            OpenListResult.failure(e)
        }
    }
    
    /** 递归计算目录总大小 */
    override suspend fun getDirectorySize(path: String): OpenListResult<Long> = withContext(Dispatchers.IO) {
        try {
            val files = list(path).getOrThrow()
            var totalSize = 0L
            for (file in files) {
                totalSize += if (file.isDirectory) {
                    getDirectorySize(file.path).getOrNull() ?: 0
                } else {
                    file.size
                }
            }
            OpenListResult.success(totalSize)
        } catch (e: Exception) {
            OpenListResult.failure(e)
        }
    }
    
    /** OpenList bridge Move(handle, srcPath, dstDirPath) — dst 是目标目录路径 */
    override suspend fun move(source: String, destination: String): OpenListResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val h = ensureHandle()
            Openlistbridge.move(h, source, destination)
            OpenListResult.success(Unit)
        } catch (e: Exception) {
            OpenListResult.failure(e)
        }
    }
    
    /** 销毁驱动实例，释放 Go 资源 */
    fun destroy() {
        handle?.let { h ->
            try { Openlistbridge.destroy(h) } catch (_: Exception) {}
            handle = null
        }
    }
}
