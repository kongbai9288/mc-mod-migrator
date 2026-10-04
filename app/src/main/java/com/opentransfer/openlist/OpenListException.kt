package com.opentransfer.openlist

/**
 * Exception thrown by OpenList operations.
 */
class OpenListException : Exception {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
    constructor(cause: Throwable) : super(cause)
}
