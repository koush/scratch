package com.koushikdutta.scratch

import com.koushikdutta.scratch.async.async
import com.koushikdutta.scratch.async.launch
import kotlin.test.assertEquals

internal fun <T> async(block: suspend() -> T) = AsyncAffinity.NO_AFFINITY.async {
    block()
}

internal fun <T> launch(block: suspend() -> T) = AsyncAffinity.NO_AFFINITY.launch {
    block()
}

internal fun <T> asyncTest(expected: T, block: suspend () -> T) {
    var result: T? = null
    async {
        result = block()
    }
    assertEquals(expected, result)
}