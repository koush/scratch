package com.koushikdutta.scratch

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.*

open class AsyncResultHolder<T>(private var finalizer: () -> Unit = {}) {
    var exception: Throwable? = null
        internal set
    var done = false
        internal set
    var value: T? = null
        internal set

    internal open fun onComplete() {
        finalizer()
    }

    internal fun setComplete(exception: Throwable?, value: T?) {
        if (exception != null)
            this.exception = exception
        else
            this.value = value
        done = true
        onComplete()
    }

    internal fun setComplete(result: Result<T>) {
        setComplete(result.exceptionOrNull(), result.getOrNull())
    }

    fun rethrow() {
        if (exception != null)
            throw exception!!
    }
}

open class AsyncResult<T>(finalizer: () -> Unit = {}) : AsyncResultHolder<T>(finalizer) {
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

class Cooperator {
    private var waiting: Continuation<Unit>? = null
    fun resume() {
        val resume = waiting
        waiting = null
        resume?.resume(Unit)
    }
    fun resumeWithException(exception: Throwable) {
        val resume = waiting
        waiting = null
        resume?.resumeWithException(exception)
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
class AsyncHandler private constructor(private val await: suspend() -> Unit) {
    private val queue = AsyncDequeueIterator<suspend() -> Unit>()
    private var blocked = false
    val job = GlobalScope.launch {
        val iter = queue.iterator()
        while (iter.hasNext()) {
            await()
            runBlock(iter.next())
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

        return suspendCoroutine {
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
