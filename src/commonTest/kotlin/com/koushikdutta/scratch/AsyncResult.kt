package com.koushikdutta.scratch

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

internal open class AsyncResult<T> : Promise<T>() {
    override fun onComplete() {
        super.onComplete()
        rethrow()
    }
}

internal fun <T> async(block: suspend() -> T): AsyncResult<T> {
    val ret = AsyncResult<T>()
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result ->
        ret.setComplete(result)
        ret.rethrow()
    })
    return ret
}
