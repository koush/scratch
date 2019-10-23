package com.koushikdutta.scratch

import kotlinx.coroutines.*
import kotlin.coroutines.*

// these scopes and contexts are no ops. AsyncAffinity object methods are guarded by await() to ensure
// thread affinity.
internal class EmptyCoroutineDispatcher : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        throw AssertionError("EmptyCoroutineDispatcher should never be dispatched")

    }
    override fun isDispatchNeeded(context: CoroutineContext): Boolean {
        return false
    }
}

internal val asyncAffinityCoroutineContext = EmptyCoroutineContext + EmptyCoroutineDispatcher()

internal val asyncAffinityCoroutineScope = object : CoroutineScope {
    override val coroutineContext: CoroutineContext = asyncAffinityCoroutineContext
}

fun <S: AsyncAffinity, T> S.async(block: suspend S.() -> T): Deferred<T> {
    val deferred = CompletableDeferred<T>()
    startSafeCoroutine {
        try {
            deferred.complete(block())
        }
        catch (exception: Exception) {
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
        catch (exception: Exception) {
            job.completeExceptionally(exception)

        }
    }
    return job
}


internal open class AsyncResultHolder<T>(private var finalizer: () -> Unit = {}) {
    var exception: Exception? = null
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
            if (exception !is Exception)
                throw exception
            this.exception = exception as Exception
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
    private val scope = CoroutineScope(EmptyCoroutineContext + EmptyCoroutineDispatcher())
    val job = scope.launch {
        for (block in queue) {
            await()
            runBlock(block)
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
