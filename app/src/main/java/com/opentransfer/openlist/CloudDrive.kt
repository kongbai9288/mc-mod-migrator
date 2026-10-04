package com.opentransfer.openlist

import java.io.File

/**
 * Interface for cloud drive operations.
 * 
 * 基于原版 OpenList Bridge API 设计：
 * - list(path) → List<CloudFile>
 * - getDownloadUrl(path) → DownloadInfo
 * - upload(parentPath, fileName, localFilePath, mimeType) → CloudFile
 * - mkdir(parentPath, dirName) → CloudFile
 * - delete(path) → void
 * - rename(path, newName) → CloudFile
 * - move(srcPath, dstDirPath) → CloudFile
 * - copy(srcPath, dstDirPath) → CloudFile
 */
interface CloudDrive {
    /**
     * Test connection to the cloud storage.
     */
    suspend fun testConnection(): OpenListResult<Unit>
    
    /**
     * List files in a directory.
     * 
     * @param path Directory path
     * @return List of files in the directory
     */
    suspend fun list(path: String): OpenListResult<List<RemoteFile>>
    
    /**
     * Get file/directory info.
     * 
     * @param path Remote path
     */
    suspend fun stat(path: String): OpenListResult<RemoteFile>
    
    /**
     * Upload a local file.
     * 
     * @param local Local file to upload
     * @param remotePath Remote path to upload to
     * @param onProgress Progress callback (bytes uploaded)
     * @return Uploaded file info
     */
    suspend fun upload(
        local: File,
        remotePath: String,
        onProgress: ((Long) -> Unit)? = null,
    ): OpenListResult<RemoteFile>
    
    /**
     * Download a remote file.
     * 
     * @param remotePath Remote file path
     * @param local Local file to save to
     * @param onProgress Progress callback (bytes downloaded)
     */
    suspend fun download(
        remotePath: String,
        local: File,
        onProgress: ((Long) -> Unit)? = null,
    ): OpenListResult<Unit>
    
    /**
     * Delete a remote file/directory.
     * 
     * @param path Remote path to delete
     */
    suspend fun delete(path: String): OpenListResult<Unit>
    
    /**
     * Create a directory.
     * 
     * @param path Directory path to create
     */
    suspend fun mkdir(path: String): OpenListResult<Unit>
    
    /**
     * Ensure a directory exists, creating it if necessary.
     * 
     * @param path Directory path
     */
    suspend fun ensureDirectory(path: String): OpenListResult<Unit>
    
    /**
     * Check if a file/directory exists.
     * 
     * @param path Remote path
     */
    suspend fun exists(path: String): OpenListResult<Boolean>
    
    /**
     * Rename/move a file.
     * 
     * @param oldPath Current path
     * @param newPath New path
     */
    suspend fun rename(oldPath: String, newPath: String): OpenListResult<Unit>
    
    /**
     * Copy a file.
     * 
     * @param source Source path
     * @param destination Destination directory path
     */
    suspend fun copy(source: String, destination: String): OpenListResult<Unit>
    
    /**
     * Move a file/directory to another directory.
     * 
     * @param source Source path
     * @param destination Destination directory path
     */
    suspend fun move(source: String, destination: String): OpenListResult<Unit>
    
    /**
     * Get download URL for a file (if supported).
     * 
     * @param path Remote file path
     */
    suspend fun getDownloadUrl(path: String): OpenListResult<String>
    
    /**
     * Get total size of a directory.
     * 
     * @param path Directory path
     */
    suspend fun getDirectorySize(path: String): OpenListResult<Long>
}
