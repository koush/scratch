package com.koushikdutta.scratch

actual inline fun <R> synchronized(lock: Any, block: () -> R): R {
    return block()
}
