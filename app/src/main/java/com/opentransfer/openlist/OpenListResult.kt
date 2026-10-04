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
        fun failure(error: OpenListException): OpenListResult<Nothing> = Failure(error)
        fun failure(message: String): OpenListResult<Nothing> = Failure(OpenListException(message))
    }
}
