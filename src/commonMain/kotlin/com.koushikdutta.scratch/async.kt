package com.koushikdutta.scratch

import kotlin.coroutines.*

fun <S: AsyncAffinity, T> S.async(block: suspend S.() -> T): Promise<T> {
    val deferred = Promise<T>()
    startSafeCoroutine {
        try {
            await()
            deferred.setComplete(null, block())
        }
        catch (exception: Throwable) {
            deferred.setComplete(exception, null)
        }
    }
    return deferred
}

class SafeCoroutineError(throwable: Throwable): Error("startSafeCoroutine should not throw", throwable)

internal fun startSafeCoroutine(block: suspend() -> Unit) {
    val wrappedBlock : suspend() -> Unit = {
        try {
            block()
        }
        catch (throwable: Throwable) {
            throw SafeCoroutineError(throwable)
        }
    }
    wrappedBlock.startCoroutine(Continuation(EmptyCoroutineContext){
        it.getOrThrow()
    })
}

internal class Cooperator {
    internal var waiting: Continuation<Unit>? = null
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
    suspend inline fun yield() {
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
        startSafeCoroutine {
            for (block in queue) {
                await()
                runBlock(block)
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

        return suspendCoroutine {
            post {
                val ret: T =
                    try {
                        block()
                    }
                    catch (t: Throwable) {
                        // fine to catch Throwables here because it is monitored
                        it.resumeWithException(t)
                        return@post
                    }
                it.resume(ret)
            }
        }
    }
}
