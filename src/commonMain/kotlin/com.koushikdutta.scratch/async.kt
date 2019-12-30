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
            println(exception)
            rethrowUnhandledAsyncException(exception)
            deferred.setComplete(exception, null)
        }
    }
    return deferred
}

class UnhandledAsyncExceptionError(throwable: Throwable): Error(throwable)
fun rethrowUnhandledAsyncException(throwable: Throwable) {
    if (throwable is UnhandledAsyncExceptionError)
        throw throwable
}
fun throwUnhandledAsyncException(throwable: Throwable) {
    rethrowUnhandledAsyncException(throwable)
    throw UnhandledAsyncExceptionError(throwable)
}

/**
 * callers of this internal function MUST rethrow UnhandledAsyncExceptionError
 */
@Deprecated(message = "deprecated for caller note: callers of this internal function MUST rethrow UnhandledAsyncExceptionError")
internal fun startSafeCoroutine(block: suspend() -> Unit) {
    val wrappedBlock : suspend() -> Unit = {
        try {
            block()
        }
        catch (exception: Throwable) {
//            println(exception)
            exitProcess(UnhandledAsyncExceptionError(exception))
            // ensure exceptions get converted to errors and rethrown,
            throw UnhandledAsyncExceptionError(exception)
        }
    }
    wrappedBlock.startCoroutine(Continuation(EmptyCoroutineContext){
        // todo: put onto event loop if its unhandled.
        it.getOrThrow()
    })
}


class Cooperator {
    var waiting: Continuation<Unit>? = null
    private fun updateWaitingLocked(update: Continuation<Unit>?): Continuation<Unit>? {
        val resume = waiting
        waiting = update
        return resume
    }
    private fun updateWaiting(update: Continuation<Unit>?): Continuation<Unit>? {
        synchronized(this) {
            return updateWaitingLocked(update)
        }
    }
    fun resume() {
        updateWaiting(null)?.resume(Unit)
    }
    fun resumeWithException(exception: Throwable) {
        updateWaiting(null)?.resumeWithException(exception)
    }
    suspend fun yield() {
        suspendCoroutine<Unit> { continuation ->
            updateWaiting(continuation)?.resume(Unit)
        }
    }
}

/**
 * Create a coroutine executor that can be used to serialize
 * suspending calls.
 */
class AsyncHandler(private val await: suspend() -> Unit) {
    private val queue = AsyncQueue<suspend() -> Unit>()
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
