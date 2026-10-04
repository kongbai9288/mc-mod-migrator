package com.opentransfer.openlist

/**
 * Represents a remote file/directory.
 */
data class RemoteFile(
    /** Full path of the file */
    val path: String,
    /** File size in bytes */
    val size: Long = 0,
    /** Last modified timestamp (milliseconds) */
    val modifiedAt: Long = 0,
    /** Whether this is a directory */
    val isDirectory: Boolean = false,
) {
    /** File name without path */
    val name: String get() = path.substringAfterLast("/").ifBlank { path }
    
    /** Parent directory path */
    val parentPath: String get() = path.substringBeforeLast("/", "")
}
