package com.koushikdutta.scratch

import kotlinx.coroutines.*
import kotlin.coroutines.*

fun <S: AsyncAffinity, T> S.async(block: suspend S.() -> T): Deferred<T> {
    val deferred = CompletableDeferred<T>()
    startSafeCoroutine {
        try {
            deferred.complete(block())
        }
        catch (exception: Throwable) {
            deferred.completeExceptionally(exception)
        }
    }
    return deferred
}

fun <S: AsyncAffinity> S.launch(block: suspend S.() -> Unit): Job {
    val job = Job()
    startSafeCoroutine {
        try {
            block()
            job.complete()
        }
        catch (exception: Throwable) {
            job.completeExceptionally(exception)
        }
    }
    return job
}


internal open class AsyncResultHolder<T>(private var finalizer: () -> Unit = {}) {
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
        if (exception != null) {
            this.exception = exception
        }
        else {
            this.value = value
        }
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

internal class SafeCoroutineError(throwable: Throwable): Error("startSafeCoroutine should not throw", throwable)

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
