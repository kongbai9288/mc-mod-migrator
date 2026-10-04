package com.opentransfer.openlist

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Main entry point for OpenList cloud storage operations.
 * 
 * Usage:
 * ```kotlin
 * // 阿里云盘
 * val client = OpenListClient.aliyunDrive(refreshToken = "token")
 * 
 * // 百度网盘
 * val client = OpenListClient.baiduNetdisk(accessToken = "token")
 * 
 * // Google Drive
 * val client = OpenListClient.googleDrive(refreshToken = "...", clientId = "...", clientSecret = "...")
 * 
 * // S3
 * val client = OpenListClient.s3(accessKey = "...", secretKey = "...", endpoint = "...", bucket = "...")
 * 
 * // Test connection
 * when (val result = client.testConnection()) {
 *     is OpenListResult.Success -> println("Connected!")
 *     is OpenListResult.Failure -> println("Failed: ${result.error.message}")
 * }
 * 
 * // List files
 * val files = client.list("/backup")
 * 
 * // Upload file
 * client.upload(File("local.tar.zst"), "/backup/archive.tar.zst")
 * 
 * // Download file
 * client.download("/backup/archive.tar.zst", File("local.tar.zst"))
 * ```
 */
class OpenListClient private constructor(
    private val drive: CloudDrive,
    private val config: OpenListConfig,
) {
    /** Provider type */
    val provider: String get() = config.provider
    
    /**
     * Test connection to the cloud storage.
     */
    suspend fun testConnection(): OpenListResult<Unit> = drive.testConnection()
    
    /**
     * List files in a directory.
     * 
     * @param path Directory path (default: "/")
     */
    suspend fun list(path: String = "/"): OpenListResult<List<RemoteFile>> = drive.list(path)
    
    /**
     * Get file/directory info.
     * 
     * @param path Remote path
     */
    suspend fun stat(path: String): OpenListResult<RemoteFile> = drive.stat(path)
    
    /**
     * Upload a local file.
     * 
     * @param local Local file to upload
     * @param remotePath Remote path to upload to
     * @param onProgress Progress callback (0-100 for percentage, or bytes uploaded)
     * @return Uploaded file info
     */
    suspend fun upload(
        local: File,
        remotePath: String,
        onProgress: ((Long) -> Unit)? = null,
    ): OpenListResult<RemoteFile> = drive.upload(local, remotePath, onProgress)
    
    /**
     * Download a remote file.
     * 
     * @param remotePath Remote file path
     * @param local Local file to save to
     * @param onProgress Progress callback (0-100 for percentage, or bytes downloaded)
     */
    suspend fun download(
        remotePath: String,
        local: File,
        onProgress: ((Long) -> Unit)? = null,
    ): OpenListResult<Unit> = drive.download(remotePath, local, onProgress)
    
    /**
     * Delete a remote file/directory.
     * 
     * @param path Remote path to delete
     */
    suspend fun delete(path: String): OpenListResult<Unit> = drive.delete(path)
    
    /**
     * Create a directory.
     * 
     * @param path Directory path to create
     */
    suspend fun mkdir(path: String): OpenListResult<Unit> = drive.mkdir(path)
    
    /**
     * Ensure a directory exists, creating it and all parent directories if necessary.
     * 
     * @param path Directory path
     */
    suspend fun ensureDirectory(path: String): OpenListResult<Unit> = drive.ensureDirectory(path)
    
    /**
     * Check if a file/directory exists.
     * 
     * @param path Remote path
     */
    suspend fun exists(path: String): OpenListResult<Boolean> = drive.exists(path)
    
    /**
     * Rename/move a file.
     * 
     * @param oldPath Current path
     * @param newPath New path
     */
    suspend fun rename(oldPath: String, newPath: String): OpenListResult<Unit> = drive.rename(oldPath, newPath)
    
    /**
     * Copy a file to another directory.
     * 
     * @param source Source path
     * @param destination Destination directory path
     */
    suspend fun copy(source: String, destination: String): OpenListResult<Unit> = drive.copy(source, destination)
    
    /**
     * Move a file/directory to another directory.
     * 
     * @param source Source path  
     * @param destination Destination directory path
     */
    suspend fun move(source: String, destination: String): OpenListResult<Unit> = drive.move(source, destination)
    
    /**
     * Get download URL for a file (if supported).
     * 
     * @param path Remote file path
     */
    suspend fun getDownloadUrl(path: String): OpenListResult<String> = drive.getDownloadUrl(path)
    
    /**
     * Get total size of a directory.
     * 
     * @param path Directory path
     */
    suspend fun getDirectorySize(path: String): OpenListResult<Long> = drive.getDirectorySize(path)
    
    /**
     * Upload a file with automatic directory creation.
     * 
     * @param local Local file to upload
     * @param remotePath Remote path to upload to
     * @param onProgress Progress callback
     */
    suspend fun uploadWithMkdir(
        local: File,
        remotePath: String,
        onProgress: ((Long) -> Unit)? = null,
    ): OpenListResult<RemoteFile> = withContext(Dispatchers.IO) {
        try {
            val parentPath = remotePath.substringBeforeLast("/", "").ifBlank { "/" }
            ensureDirectory(parentPath).getOrThrow()
            upload(local, remotePath, onProgress)
        } catch (e: Exception) {
            OpenListResult.failure(e)
        }
    }
    
    /**
     * Batch upload multiple files.
     * 
     * @param files List of (local file, remote path) pairs
     * @param onProgress Progress callback (current index, total count, file name)
     * @return List of results for each file
     */
    suspend fun batchUpload(
        files: List<Pair<File, String>>,
        onProgress: ((index: Int, total: Int, fileName: String) -> Unit)? = null,
    ): List<OpenListResult<RemoteFile>> = withContext(Dispatchers.IO) {
        files.mapIndexed { index, (local, remotePath) ->
            onProgress?.invoke(index, files.size, local.name)
            uploadWithMkdir(local, remotePath)
        }
    }
    
    /**
     * Batch download multiple files.
     * 
     * @param files List of (remote path, local file) pairs
     * @param onProgress Progress callback (current index, total count, file name)
     * @return List of results for each file
     */
    suspend fun batchDownload(
        files: List<Pair<String, File>>,
        onProgress: ((index: Int, total: Int, fileName: String) -> Unit)? = null,
    ): List<OpenListResult<Unit>> = withContext(Dispatchers.IO) {
        files.mapIndexed { index, (remotePath, local) ->
            onProgress?.invoke(index, files.size, local.name)
            download(remotePath, local)
        }
    }
    
    /**
     * Sync a local directory to remote (upload new/changed files).
     * 
     * @param localDir Local directory
     * @param remoteDir Remote directory
     * @param onProgress Progress callback
     * @return Number of files uploaded
     */
    suspend fun syncUpload(
        localDir: File,
        remoteDir: String,
        onProgress: ((index: Int, total: Int, fileName: String) -> Unit)? = null,
    ): OpenListResult<Int> = withContext(Dispatchers.IO) {
        try {
            ensureDirectory(remoteDir).getOrThrow()
            
            val localFiles = localDir.listFiles()?.filter { it.isFile } ?: emptyList()
            var uploaded = 0
            
            localFiles.forEachIndexed { index, file ->
                onProgress?.invoke(index, localFiles.size, file.name)
                val remotePath = "$remoteDir/${file.name}"
                
                // Check if file exists and is different size
                val remoteInfo = stat(remotePath).getOrNull()
                if (remoteInfo == null || remoteInfo.size != file.length()) {
                    upload(file, remotePath).getOrThrow()
                    uploaded++
                }
            }
            
            OpenListResult.success(uploaded)
        } catch (e: Exception) {
            OpenListResult.failure(e)
        }
    }
    
    /**
     * Destroy the driver instance and release Go runtime resources.
     * Call this when the client is no longer needed.
     */
    fun destroy() {
        (drive as? OpenListDrive)?.destroy()
    }
    
    /**
     * Get configuration
     */
    fun getConfig(): OpenListConfig = config
    
    companion object {
        // ═══════════════════════════════════════════════════════
        // 🏭 通用工厂方法
        // ═══════════════════════════════════════════════════════

        /**
         * Create a new OpenListClient from config.
         * 
         * @param config Configuration for the cloud storage
         * @return OpenListClient instance
         */
        fun create(config: OpenListConfig): OpenListClient {
            val drive = OpenListDrive(config.provider, config.configJson)
            return OpenListClient(drive, config)
        }

        /**
         * Create a new OpenListClient from JSON config.
         * 
         * @param json JSON string: {"provider": "...", "config": {...}}
         * @return OpenListClient instance
         */
        fun fromJson(json: String): OpenListClient {
            return create(OpenListConfig.fromJson(json))
        }
        
        // ═══════════════════════════════════════════════════════
        // 🇨🇳 国内云盘
        // ═══════════════════════════════════════════════════════

        /**
         * 阿里云盘 (Aliyun Drive)
         */
        fun aliyunDrive(
            refreshToken: String,
            apiUrl: String = "https://api.oplist.org/alicloud/renewapi",
            rootFolderId: String = "root",
        ): OpenListClient {
            return create(OpenListConfig.aliyunDrive(refreshToken, apiUrl, rootFolderId))
        }
        
        /**
         * 百度网盘 (Baidu Netdisk)
         */
        fun baiduNetdisk(
            accessToken: String,
            refreshToken: String = "",
            clientId: String = "",
            clientSecret: String = "",
        ): OpenListClient {
            return create(OpenListConfig.baiduNetdisk(accessToken, refreshToken, clientId, clientSecret))
        }
        
        /**
         * 夸克网盘 (Quark)
         */
        fun quark(cookie: String): OpenListClient {
            return create(OpenListConfig.quark(cookie))
        }
        
        /**
         * 天翼云盘 (Tianyi Cloud)
         */
        fun tianyiCloud(
            accessToken: String,
            refreshToken: String = "",
        ): OpenListClient {
            return create(OpenListConfig.tianyiCloud(accessToken, refreshToken))
        }
        
        /**
         * 123网盘 (123 Pan)
         */
        fun pan123(token: String): OpenListClient {
            return create(OpenListConfig.pan123(token))
        }
        
        /**
         * 115网盘 (115 Cloud)
         */
        fun cloud115(cookie: String): OpenListClient {
            return create(OpenListConfig.cloud115(cookie))
        }
        
        /**
         * 蓝奏云 (Lanzou)
         */
        fun lanzou(
            username: String,
            password: String,
            shareId: String = "",
        ): OpenListClient {
            return create(OpenListConfig.lanzou(username, password, shareId))
        }
        
        // ═══════════════════════════════════════════════════════
        // 🌍 国际云盘
        // ═══════════════════════════════════════════════════════

        /**
         * Google Drive
         */
        fun googleDrive(
            refreshToken: String,
            clientId: String,
            clientSecret: String,
        ): OpenListClient {
            return create(OpenListConfig.googleDrive(refreshToken, clientId, clientSecret))
        }
        
        /**
         * OneDrive
         */
        fun oneDrive(
            refreshToken: String,
            clientId: String,
            clientSecret: String,
        ): OpenListClient {
            return create(OpenListConfig.oneDrive(refreshToken, clientId, clientSecret))
        }
        
        /**
         * Dropbox
         */
        fun dropbox(accessToken: String): OpenListClient {
            return create(OpenListConfig.dropbox(accessToken))
        }
        
        /**
         * Box
         */
        fun box(
            refreshToken: String,
            clientId: String,
            clientSecret: String,
        ): OpenListClient {
            return create(OpenListConfig.box(refreshToken, clientId, clientSecret))
        }
        
        // ═══════════════════════════════════════════════════════
        // ☁️ 对象存储
        // ═══════════════════════════════════════════════════════

        /**
         * S3 兼容存储 (AWS S3, MinIO, DigitalOcean Spaces, etc.)
         */
        fun s3(
            accessKey: String,
            secretKey: String,
            endpoint: String,
            region: String = "us-east-1",
            bucket: String,
            pathStyleAccess: Boolean = false,
        ): OpenListClient {
            return create(OpenListConfig.s3(accessKey, secretKey, endpoint, region, bucket, pathStyleAccess))
        }
        
        /**
         * Google Cloud Storage
         */
        fun googleCloudStorage(
            bucket: String,
            serviceAccountJson: String,
        ): OpenListClient {
            return create(OpenListConfig.googleCloudStorage(bucket, serviceAccountJson))
        }
        
        /**
         * Azure Blob Storage
         */
        fun azureBlob(
            accountName: String,
            accountKey: String,
            endpoint: String = "",
            container: String,
        ): OpenListClient {
            return create(OpenListConfig.azureBlob(accountName, accountKey, endpoint, container))
        }
        
        /**
         * 阿里云 OSS (Alibaba Cloud OSS)
         */
        fun aliyunOss(
            accessKey: String,
            secretKey: String,
            endpoint: String,
            bucket: String,
        ): OpenListClient {
            return create(OpenListConfig.aliyunOss(accessKey, secretKey, endpoint, bucket))
        }
        
        /**
         * 腾讯云 COS (Tencent Cloud COS)
         */
        fun tencentCOS(
            accessKey: String,
            secretKey: String,
            region: String,
            bucket: String,
        ): OpenListClient {
            return create(OpenListConfig.tencentCOS(accessKey, secretKey, region, bucket))
        }
        
        /**
         * 华为云 OBS (Huawei Cloud OBS)
         */
        fun huaweiOBS(
            accessKey: String,
            secretKey: String,
            endpoint: String,
            bucket: String,
        ): OpenListClient {
            return create(OpenListConfig.huaweiOBS(accessKey, secretKey, endpoint, bucket))
        }
        
        /**
         * Cloudflare R2
         */
        fun cloudflareR2(
            accessKey: String,
            secretKey: String,
            endpoint: String,
            bucket: String,
        ): OpenListClient {
            return create(OpenListConfig.cloudflareR2(accessKey, secretKey, endpoint, bucket))
        }
        
        // ═══════════════════════════════════════════════════════
        // 📁 文件传输协议
        // ═══════════════════════════════════════════════════════

        /**
         * FTP
         */
        fun ftp(
            host: String,
            port: Int = 21,
            username: String,
            password: String,
        ): OpenListClient {
            return create(OpenListConfig.ftp(host, port, username, password))
        }
        
        /**
         * SFTP
         */
        fun sftp(
            host: String,
            port: Int = 22,
            username: String,
            password: String = "",
            privateKey: String = "",
        ): OpenListClient {
            return create(OpenListConfig.sftp(host, port, username, password, privateKey))
        }
        
        /**
         * WebDAV
         */
        fun webDav(
            url: String,
            username: String,
            password: String,
        ): OpenListClient {
            return create(OpenListConfig.webDav(url, username, password))
        }
        
        /**
         * 本地存储 (Local)
         */
        fun local(rootPath: String): OpenListClient {
            return create(OpenListConfig.local(rootPath))
        }
    }
}
