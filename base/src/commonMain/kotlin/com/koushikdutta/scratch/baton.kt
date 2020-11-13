package com.koushikdutta.scratch

import com.koushikdutta.scratch.atomic.FreezableReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine

private fun <T> Continuation<T>.resume(result: Result<T>) {
    result.resume(this)
}

class BatonResult<T>(result: Result<T>, val resumed: Boolean, val finished: Boolean): Result<T> by result
typealias BatonLock<T, R> = (result: BatonResult<T>) -> R
typealias BatonTossLock<T, R> = (result: BatonResult<T>?) -> R
typealias BatonTakeCondition<T> = (result: BatonResult<T>) -> Boolean

private fun <T, R> BatonTossLock<T, R>.resultInvoke(result: BatonResult<T>?): Result<R> {
    return try {
        Result.success(invoke(result))
    }
    catch (throwable: Throwable) {
        Result.failure(throwable)
    }
}

private data class BatonData<T, R>(val result: Result<T>, val lock: BatonTossLock<T, R>?)
private data class BatonContinuationLockedData<T, RR>(val continuation: Continuation<RR>?, val data: BatonData<T, RR>?, val frozen: Boolean) {
    fun <R> runLockBlocks(result: Result<T>, lock: BatonTossLock<T, R>?, immediate: Boolean, finish: Boolean): BatonContinuationData<T, R, RR> {
        val cdata = this
        val data = cdata.data
        val resumeResult: Result<R>?
        val pendingResult: Result<RR>?

        // process locks and callbacks in the order they were received
        if (data != null) {
            pendingResult = data.lock?.resultInvoke(
                BatonResult(
                    result,
                    false,
                    finish
                )
            )
            val batonResult =
                BatonResult(data.result, true, frozen)
            resumeResult = lock?.resultInvoke(batonResult)
        }
        else {
            if (immediate)
                resumeResult = lock?.resultInvoke(null)
            else
                resumeResult = null
            pendingResult = null
        }

        return BatonContinuationData(
            cdata.continuation,
            pendingResult,
            resumeResult
        )
    }
}

private data class BatonContinuationData<T, R, RR>(val pendingContinuation: Continuation<RR>?, val pendingResult: Result<RR>?, val resumeResult: Result<R>?) {
    fun resumeContinuations(resumingContinuation: Continuation<R>?): R? {
        // process locks and callbacks in the order they were received

        if (pendingResult != null)
            pendingContinuation?.resume(pendingResult)

        if (resumeResult != null)
            resumingContinuation?.resume(resumeResult)

        if (resumingContinuation == null)
            resumeResult?.getOrThrow()

        return resumeResult?.getOrThrow()
    }

    fun resumeContinuationImmediate(resumingContinuation: Continuation<R>?): R {
        // process locks and callbacks in the order they were received

        if (pendingResult != null)
            pendingContinuation?.resume(pendingResult)

        resumingContinuation?.resume(resumeResult!!)

        if (resumingContinuation == null)
            resumeResult!!.getOrThrow()

        return resumeResult!!.getOrThrow()
    }
}

private data class BatonWaiter<T, R>(val continuation: Continuation<R>?, val data: BatonData<T, R>, val frozen: Boolean) {
    fun getContinuationLockedData(): BatonContinuationLockedData<T, R> {
        return BatonContinuationLockedData(continuation, data, frozen)
    }
}

class Baton<T> {
    private val freeze = FreezableReference<BatonWaiter<T, *>>()

    private fun <R> passInternal(result: Result<T>, lock: BatonTossLock<T, R>? = null, take: BatonTakeCondition<T>? = null, finish: Boolean = false, continuation: Continuation<R>? = null): R? {
        return runInternal(result, lock, take, finish, continuation).resumeContinuations(continuation)
    }

    private fun <R> tossInternal(result: Result<T>, lock: BatonTossLock<T, R>? = null, take: BatonTakeCondition<T>? = null, finish: Boolean = false, continuation: Continuation<R>? = null): R {
        return runInternal(result, lock, take, finish, continuation).resumeContinuationImmediate(continuation)
    }

    private fun <R> runInternal(result: Result<T>, lock: BatonTossLock<T, R>? = null, take: BatonTakeCondition<T>? = null, finish: Boolean = false, continuation: Continuation<R>? = null): BatonContinuationData<T, R, *> {
        val immediate = continuation == null
        val dataLock = if (immediate) null else lock
        val cdata = if (finish) {
            val finishData = BatonData(result, dataLock)
            val found = freeze.freeze(BatonWaiter(null, finishData, true))
            if (found != null)
                found.value.getContinuationLockedData()
            else
                BatonContinuationLockedData(null, null, false)
        }
        else if (take != null) {
            val taken: BatonContinuationLockedData<T, out Any?>
            while (true) {
                val found = freeze.get()
                if (found == null) {
                    taken = BatonContinuationLockedData(null, null, false)
                    break
                }
                if (take(
                        BatonResult(
                            found.value.data.result,
                            true,
                            found.frozen
                        )
                    )) {
                    if (found.frozen || freeze.compareAndSetNull(found)) {
                        taken = found.value.getContinuationLockedData()
                        break
                    }
                }
                else {
                    taken = BatonContinuationLockedData(null, null, false)
                    break
                }
            }
            taken
        }
        else {
            val resume = freeze.nullSwap()
            if (resume != null) {
                // fast path with no extra allocations in case there's a waiter
                resume.value.getContinuationLockedData()
            }
            else {
                // slow path with allocations and spin lock
                val waiter = freeze.swapIfNullElseNull(
                    BatonWaiter(
                        continuation,
                        BatonData(result, dataLock),
                        false
                    )
                )
                if (waiter == null)
                    BatonContinuationLockedData(null, null, false)
                else
                    waiter.value.getContinuationLockedData()
            }
        }

        return cdata.runLockBlocks(result, lock, immediate, finish)
    }


    private suspend fun <R> pass(result: Result<T>, lock: BatonLock<T, R>? = null): R = suspendCoroutine {
        val wrappedLock: BatonTossLock<T, R>? =
        if (lock != null) {
            { result ->
                lock(result!!)
            }
        }
        else {
            null
        }
        passInternal(result, wrappedLock, continuation = it)
    }

    fun rethrow() {
        val finishData = freeze.getFrozen()
        finishData?.data?.result?.getOrThrow()
    }

    val isFinished: Boolean
        get() = freeze.isFrozen

    private val defaultTossLock: BatonTossLock<T, T?> = {
        it?.getOrThrow()
    }

    fun takeIf(value: T, takeCondition: BatonTakeCondition<T>): T? {
        return tossInternal(Result.success(value), take = takeCondition, lock = defaultTossLock)
    }

    fun <R> takeIf(value: T, takeCondition: BatonTakeCondition<T>, tossLock: BatonTossLock<T, R>): R {
        return tossInternal(Result.success(value), take = takeCondition, lock = tossLock)
    }

    private val defaultTakeCondition: BatonTakeCondition<T> = { true }
    fun take(value: T): T? {
        return tossInternal(Result.success(value), take = defaultTakeCondition, lock = defaultTossLock)
    }

    fun takeRaise(throwable: Throwable): T? {
        return tossInternal(Result.failure(throwable), take = defaultTakeCondition, lock = defaultTossLock)
    }

    fun <R> take(value: T, tossLock: BatonTossLock<T, R>): R {
        return tossInternal(Result.success(value), take = defaultTakeCondition, lock = tossLock)
    }

    fun <R> takeRaise(throwable: Throwable, tossLock: BatonTossLock<T, R>): R {
        return tossInternal(Result.failure(throwable), take = defaultTakeCondition, lock = tossLock)
    }

    fun toss(value: T): T? {
        return tossInternal(Result.success(value), lock = defaultTossLock)
    }

    fun tossRaise(throwable: Throwable): T? {
        return tossInternal(Result.failure(throwable), lock = defaultTossLock)
    }

    fun <R> toss(value: T, tossLock: BatonTossLock<T, R>): R {
        return tossInternal(Result.success(value), lock = tossLock)
    }

    fun <R> tossRaise(throwable: Throwable, tossLock: BatonTossLock<T, R>): R {
        return tossInternal(Result.failure(throwable), lock = tossLock)
    }

    private val defaultFinishLock: BatonTossLock<T, BatonResult<T>?> = {
        it?.getOrThrow()
        it
    }

    fun finish(value: T): BatonResult<T>? {
        return passInternal(Result.success(value), finish = true, lock = defaultFinishLock)
    }

    fun raiseFinish(throwable: Throwable): BatonResult<T>? {
        return passInternal(Result.failure(throwable), finish = true, lock = defaultFinishLock)
    }

    fun <R> finish(value: T, tossLock: BatonTossLock<T, R>): R {
        return passInternal(Result.success(value), finish = true, lock = tossLock)!!
    }

    fun <R> raiseFinish(throwable: Throwable, tossLock: BatonTossLock<T, R>): R {
        return passInternal(Result.failure(throwable), finish = true, lock = tossLock)!!
    }

    private val defaultLock: BatonLock<T, T?> = { result ->
        result.getOrThrow()
    }

    suspend fun pass(value: T): T {
        return pass(Result.success(value), defaultLock)!!
    }

    suspend fun <R> pass(value: T, lock: BatonLock<T, R>): R {
        return pass(Result.success(value), lock)!!
    }

    suspend fun raise(throwable: Throwable): T {
        return pass(Result.failure(throwable), defaultLock)!!
    }

    suspend fun <R> raise(throwable: Throwable, lock: BatonLock<T, R>): R {
        return pass(Result.failure(throwable), lock)!!
    }
}
