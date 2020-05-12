package com.koushikdutta.scratch

internal fun <T> async(block: suspend() -> T): Promise<T> {
    val ret = Promise(block)
    ret.rethrowIfDone()
    return ret
}
