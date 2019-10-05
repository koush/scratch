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

/**
 * Create a coroutine executor that can be used to serialize
 * suspending calls.
 */
class AsyncHandler(private val await: suspend() -> Unit) {
    private val queue = AsyncDequeueIterator<suspend() -> Unit>()
    private var blocked = false

    init {
        async {
            val iter = queue.iterator()
            while (iter.hasNext()) {
                await()
                runBlock(iter.next())
            }
        }
    }

    fun post(block: suspend() -> Unit) {
        queue.add(block)
    }

    // run a block. requires that the call is on the affinity thread.
    private suspend fun <T> runBlock(block: suspend() -> T): T {
        blocked = true
        try {
            return block()
        }
        finally {
            blocked = false
        }
    }

    suspend fun <T> run(block: suspend() -> T): T {
        await()

        // fast(?) path in case there's nothing in the queue
        // unsure the cost of coroutines, but this prevents a queue/iterator/suspend hit.
        if (!blocked) {
            return runBlock(block)
        }

        return suspendCoroutine post@{
            post {
                val ret: T =
                try {
                    block()
                }
                catch (t: Throwable) {
                    it.resumeWithException(t)
                    return@post
                }
                it.resume(ret)
            }
        }
    }
}
