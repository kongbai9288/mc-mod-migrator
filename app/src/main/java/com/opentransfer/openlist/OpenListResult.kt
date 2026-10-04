package com.opentransfer.openlist

/**
 * Result wrapper for OpenList operations.
 */
sealed class OpenListResult<out T> {
    data class Success<T>(val data: T) : OpenListResult<T>()
    data class Failure(val error: OpenListException) : OpenListResult<Nothing>()
    
    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure
    
    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Failure -> null
    }
    
    fun exceptionOrNull(): OpenListException? = when (this) {
        is Success -> null
        is Failure -> error
    }
    
    fun <R> map(transform: (T) -> R): OpenListResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Failure -> this
    }
    
    fun getOrThrow(): T = when (this) {
        is Success -> data
        is Failure -> throw error
    }
    
    companion object {
        fun <T> success(data: T): OpenListResult<T> = Success(data)

        //
        // ⚠️ 上游 SDK 源码自身编译不过：
        // 调用方到处写 `OpenListResult.failure(e)`，而 e 是 Exception；
        // 这里原来只声明了 `failure(error: OpenListException)`，
        // 于是整包 Kotlin 编译失败（README 里是写了 failure(message) 的，
        // 但源码里没给 Exception 的重载）。
        //
        // 补两个重载：Throwable 与 String 都收。
        // 注意返回类型写成泛型 T —— 原来固定 Nothing，
        // 在需要 OpenListResult<RemoteFile> 的地方会推导不出来。
        //
        fun <T> failure(error: OpenListException): OpenListResult<T> = Failure(error)
        fun <T> failure(t: Throwable): OpenListResult<T> =
            Failure(OpenListException(t.message ?: t.javaClass.simpleName))
        fun <T> failure(message: String): OpenListResult<T> = Failure(OpenListException(message))
    }
}
