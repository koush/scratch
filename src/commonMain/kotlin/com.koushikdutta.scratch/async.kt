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

data class BatonResult<T>(val value: T, val resumed: Boolean)
typealias BatonLock<T> = (result: BatonResult<T>) -> Unit
private class BatonData<T>(val throwable: Throwable?, val value: T?, val lock: BatonLock<T>?)

class Baton<T> {
    private var waiter: Continuation<BatonResult<T>>? = null
    private var waiting: BatonData<T>? = null
    private var finishData: BatonData<T>? = null

    private suspend fun pass(throwable: Throwable?, value: T?, lock: BatonLock<T>? = null, finish: Boolean = false): BatonResult<T>? = suspendCoroutine {
        val pair = synchronized(this) {
            val pair =
            if (finishData != null) {
                if (finish)
                    Pair(null, BatonData<T>(Exception("Baton already closed"), null, null))
                else
                    Pair(null, finishData)
            }
            else if (waiter != null) {
                // value already available
                val resume = waiter
                val current = waiting!!
                waiter = null
                waiting = null
                if (finish) {
                    finishData = BatonData(throwable, value, lock)
                    Pair(resume, null)
                }
                else {
                    Pair(resume, current)
                }
            }
            else if (finish) {
                finishData = BatonData(throwable, value, lock)
                Pair(null, null)
            }
            else {
                // need to wait for value
                waiter = it
                waiting = BatonData(throwable, value, lock)
                Pair(null, null)
            }

            val data = pair.second
            if (data != null) {
                if (data.throwable == null)
                    lock?.invoke(BatonResult(data.value!!, true))
                if (throwable == null)
                    data.lock?.invoke(BatonResult(value!!, false))
            }

            pair
        }
        // resume outside of the synchronized lock
        val data = pair.second
        if (data != null) {
            if (data.throwable != null)
                it.resumeWithException(data.throwable)
            else
                it.resume(BatonResult(data.value!!, true))

            val resume = pair.first
            if (throwable != null)
                resume?.resumeWithException(throwable)
            else
                resume?.resume(BatonResult(value!!, false))
        }
        else if (finish) {
            it.resume(null)
        }
    }

    fun rethrow() {
        if (finishData?.throwable != null)
            throw finishData!!.throwable!!
    }

    suspend fun finish(value: T) {
        pass(null, value, finish = true)
    }

    suspend fun finish(throwable: Throwable) {
        pass(throwable, null, finish = true)
    }

    suspend fun pass(value: T): T {
        return pass(null, value)!!.value
    }

    suspend fun passResult(value: T, lock: BatonLock<T>? = null): BatonResult<T> {
        return pass(null, value, lock)!!
    }

    suspend fun raise(throwable: Throwable): T {
        return pass(throwable, null)!!.value
    }

    suspend fun raiseResult(throwable: Throwable, lock: BatonLock<T>? = null): BatonResult<T> {
        return pass(throwable, null, lock)!!
    }
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
