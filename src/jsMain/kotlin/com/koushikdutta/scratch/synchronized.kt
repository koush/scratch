package com.koushikdutta.scratch

actual fun <R> synchronized(lock: Any, block: () -> R): R {
    return block()
}
