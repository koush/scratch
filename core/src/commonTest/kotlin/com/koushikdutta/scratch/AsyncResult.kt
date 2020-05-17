package com.koushikdutta.scratch

import kotlin.test.assertEquals

internal fun <T> async(block: suspend() -> T): Promise<T> {
    val ret = Promise(block)
    ret.rethrowIfDone()
    return ret
}

internal fun <T> asyncTest(expected: T, block: suspend () -> T) {
    var result: T? = null
    async {
        result = block()
    }
    assertEquals(expected, result)
}