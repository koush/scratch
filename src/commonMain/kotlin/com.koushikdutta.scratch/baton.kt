package com.koushikdutta.scratch

import com.koushikdutta.scratch.atomic.FreezableReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

open class LockResult<R>(val throwable: Throwable?, val value: R?) {
    val isSuccess = throwable == null
    val isFailure = throwable != null
    fun rethrow() {
        if (throwable != null)
            throw throwable
    }
}
private fun <T> Continuation<T>.resume(result: LockResult<T>) {
    if (result.throwable != null)
        resumeWithException(result.throwable)
    else
        resume(result.value!!)
}

class BatonResult<T>(throwable: Throwable?, value: T?, val resumed: Boolean, val finished: Boolean): LockResult<T>(throwable, value)
typealias BatonLock<T, R> = (result: BatonResult<T>) -> R
typealias BatonTossLock<T, R> = (result: BatonResult<T>?) -> R

private fun <T, R> BatonTossLock<T, R>.resultInvoke(result: BatonResult<T>?): LockResult<R> {
    return try {
        LockResult(null, invoke(result))
    }
    catch (throwable: Throwable) {
        LockResult(throwable, null)
    }
}

private data class BatonData<T, R>(val throwable: Throwable?, val value: T?, val lock: BatonTossLock<T, R>?)
private data class BatonContinuationLockedData<T, RR>(val continuation: Continuation<RR>?, val data: BatonData<T, RR>?, val frozen: Boolean) {
    fun <R> runLockBlocks(throwable: Throwable?, value: T?, lock: BatonTossLock<T, R>?, immediate: Boolean, finish: Boolean): BatonContinuationData<T, R, RR> {
        val cdata = this
        val data = cdata.data
        val resumeResult: LockResult<R>?
        val pendingResult: LockResult<RR>?

        // process locks and callbacks in the order they were received
        if (data != null) {
            pendingResult = data.lock?.resultInvoke(BatonResult(throwable, value, false, finish))
            val batonResult = BatonResult(data.throwable, data.value, true, frozen)
            resumeResult = lock?.resultInvoke(batonResult)
        }
        else {
            if (immediate)
                resumeResult = lock?.resultInvoke(null)
            else
                resumeResult = null
            pendingResult = null
        }

        return BatonContinuationData(cdata.continuation, pendingResult, resumeResult)
    }
}

private data class BatonContinuationData<T, R, RR>(val pendingContinuation: Continuation<RR>?, val pendingResult: LockResult<RR>?, val resumeResult: LockResult<R>?) {
    fun resumeContinuations(resumingContinuation: Continuation<R>?): R? {
        // process locks and callbacks in the order they were received

        if (pendingResult != null)
            pendingContinuation?.resume(pendingResult)

        if (resumeResult != null)
            resumingContinuation?.resume(resumeResult)

        if (resumingContinuation == null)
            resumeResult?.rethrow()

        return resumeResult?.value
    }
}

private data class BatonWaiter<T, R>(val continuation: Continuation<R>?, val data: BatonData<T, R>, val frozen: Boolean) {
    fun getContinuationLockedData(): BatonContinuationLockedData<T, R> {
        return BatonContinuationLockedData(continuation, data, frozen)
    }
}

class Baton<T>() {
    private val freeze = FreezableReference<BatonWaiter<T, *>>()

    private fun <R> passInternal(throwable: Throwable?, value: T?, lock: BatonTossLock<T, R>? = null, take: Boolean = false, finish: Boolean = false, continuation: Continuation<R>? = null): R? {
        val immediate = continuation == null
        val dataLock = if (immediate) null else lock
        val cdata = if (finish) {
            val finishData = BatonData(throwable, value, dataLock)
            val found = freeze.freeze(BatonWaiter(null, finishData, true))
            if (found != null)
                found.value.getContinuationLockedData()
            else
                BatonContinuationLockedData(null, null, false)
        }
        else {
            val resume = freeze.nullSwap()
            if (resume != null) {
                // fast path with no extra allocations in case there's a waiter
                resume.value.getContinuationLockedData()
            }
            else if (take) {
                BatonContinuationLockedData(null, null, false)
            }
            else {
                // slow path with allocations and spin lock
                val waiter = freeze.swapIfNullElseNull(BatonWaiter(continuation, BatonData(throwable, value, dataLock), false))
                if (waiter == null)
                    BatonContinuationLockedData(null, null, false)
                else
                    waiter.value.getContinuationLockedData()
            }
        }

        return cdata.runLockBlocks(throwable, value, lock, immediate, finish).resumeContinuations(continuation)
    }


    private suspend fun <R> pass(throwable: Throwable?, value: T?, lock: BatonLock<T, R>? = null): R = suspendCoroutine {
        val wrappedLock: BatonTossLock<T, R>? =
        if (lock != null) {
            { result ->
                lock(result!!)
            }
        }
        else {
            null
        }
        passInternal(throwable, value, wrappedLock, continuation = it)
    }

    fun rethrow() {
        val finishData = freeze.getFrozen()
        if (finishData?.data?.throwable != null)
            throw finishData.data.throwable
    }

    val isFinished: Boolean
        get() = freeze.isFrozen

    private val defaultTossLock: BatonTossLock<T, T?> = {
        it?.value
    }

    fun take(value: T): T? {
        return passInternal(null, value, take = true, lock = defaultTossLock)
    }

    fun takeRaise(throwable: Throwable): T? {
        return passInternal(throwable, null, take = true, lock = defaultTossLock)
    }

    fun <R> take(value: T, tossLock: BatonTossLock<T, R>): R {
        return passInternal(null, value, take = true, lock = tossLock)!!
    }

    fun <R> takeRaise(throwable: Throwable, tossLock: BatonTossLock<T, R>): R {
        return passInternal(throwable, null, take = true, lock = tossLock)!!
    }

    fun toss(value: T): T? {
        return passInternal(null, value, lock = defaultTossLock)
    }

    fun tossRaise(throwable: Throwable): T? {
        return passInternal(throwable, null, lock = defaultTossLock)
    }

    fun <R> toss(value: T, tossLock: BatonTossLock<T, R>): R {
        return passInternal(null, value, lock = tossLock)!!
    }

    fun <R> tossRaise(throwable: Throwable, tossLock: BatonTossLock<T, R>): R {
        return passInternal(throwable, null, lock = tossLock)!!
    }

    private val defaultFinishLock: BatonTossLock<T, BatonResult<T>?> = {
        it
    }

    fun finish(value: T): BatonResult<T>? {
        return passInternal(null, value, finish = true, lock = defaultFinishLock)
    }

    fun raiseFinish(throwable: Throwable): BatonResult<T>? {
        return passInternal(throwable, null, finish = true, lock = defaultFinishLock)
    }

    fun <R> finish(value: T, tossLock: BatonTossLock<T, R>): R {
        return passInternal(null, value, finish = true, lock = tossLock)!!
    }

    fun <R> raiseFinish(throwable: Throwable, tossLock: BatonTossLock<T, R>): R {
        return passInternal(throwable, null, finish = true, lock = tossLock)!!
    }

    private val defaultLock: BatonLock<T, T?> = { result ->
        result.rethrow()
        result.value
    }

    suspend fun pass(value: T): T {
        return pass(null, value, defaultLock)!!
    }

    suspend fun <R> pass(value: T, lock: BatonLock<T, R>): R {
        return pass(null, value, lock)!!
    }

    suspend fun raise(throwable: Throwable): T {
        return pass(throwable, null, defaultLock)!!
    }

    suspend fun <R> raise(throwable: Throwable, lock: BatonLock<T, R>): R {
        return pass(throwable, null, lock)!!
    }
}
