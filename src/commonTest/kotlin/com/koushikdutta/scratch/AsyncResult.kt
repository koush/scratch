package com.koushikdutta.scratch

internal fun <T> async(block: suspend() -> T): Promise<T> {
    return Promise(block)
}
