package com.koushikdutta.scratch

import kotlin.coroutines.*

class AsyncResult<T> {
    private var exception: Throwable? = null
    private var done = false
    private var result: T? = null

    internal fun setComplete(result: Result<T>) {
        if (result.isFailure)
            this.exception = result.exceptionOrNull()
        else
            this.result = result.getOrNull()
        done = true
        try {
            if (exception != null)
                catcher(exception!!)
        }
        finally {
            finalizer()
        }
    }

    fun rethrow() {
        if (exception != null)
            throw exception!!
    }

    private var catcher: (throwable: Throwable) -> Unit = {
        throw it
    }
    private var finalizer: () -> Unit = {}
    fun catch(catcher: (throwable: Throwable) -> Unit): AsyncResult<T> {
        this.catcher = catcher
        return this
    }

    fun finally(finalizer: () -> Unit): AsyncResult<T> {
        this.finalizer = finalizer
        return this
    }
}

internal fun <T> async(block: suspend() -> T): AsyncResult<T> {
    val ret = AsyncResult<T>()
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result ->
        ret.setComplete(result)
    })
    return ret
}

internal suspend fun <T> await(block: (continuation: Continuation<T>) -> Unit): T {
    return suspendCoroutine { continuation ->
        block(continuation)
    }
}

class Cooperator {
    private var waiting: Continuation<Unit>? = null
    fun resume() {
        val resume = waiting
        waiting = null
        resume?.resume(Unit)
    }
    suspend fun yield() {
        val resume = waiting
        waiting = null
        suspendCoroutine<Unit> { continuation ->
            waiting = continuation
            resume?.resume(Unit)
        }
    }
}
