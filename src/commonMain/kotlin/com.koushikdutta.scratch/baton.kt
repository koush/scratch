package com.koushikdutta.scratch

import com.koushikdutta.scratch.atomic.FreezableAtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

data class BatonResult<T>(val throwable: Throwable?, val value: T?, val resumed: Boolean, val finished: Boolean)  {
    val isSuccess = throwable == null
    val isFailure = throwable != null
    fun rethrow() {
        if (throwable != null)
            throw throwable
    }
}
typealias BatonLock<T, R> = (result: BatonResult<T>) -> R
typealias BatonTossLock<T, R> = (result: BatonResult<T>?) -> R
private data class BatonData<T, R>(val throwable: Throwable?, val value: T?, val lock: BatonTossLock<T, R>?)
private data class BatonContinuationLockedData<T, RR>(val continuation: Continuation<RR>?, val data: BatonData<T, RR>?) {
    fun <R> runLockBlocks(throwable: Throwable?, value: T?, lock: BatonTossLock<T, R>?, immediate: Boolean, finish: Boolean): BatonContinuationData<T, R, RR> {
        val cdata = this
        val data = cdata.data
        val resumeResult: R?
        val pendingResult: RR?
        // a toss may not have a pending result.
        if (data != null) {
            val batonResult = BatonResult(data.throwable, data.value, true, finish)
            resumeResult = lock?.invoke(batonResult)
            pendingResult = data.lock?.invoke(BatonResult(throwable, value, false, finish))
        }
        else {
            if (immediate)
                resumeResult = lock?.invoke(null)
            else
                resumeResult = null
            pendingResult = null
        }

        return BatonContinuationData(cdata.continuation, cdata.data, pendingResult, resumeResult)
    }
}

private data class BatonContinuationData<T, R, RR>(val pendingContinuation: Continuation<RR>?, val data: BatonData<T, RR>?, val pendingResult: RR?, val resumeResult: R?) {
    fun resumeContinuations(throwable: Throwable?, resumingContinuation: Continuation<R>?): R? {
        if (data == null)
            return resumeResult

        if (data.throwable != null)
            resumingContinuation?.resumeWithException(data.throwable)
        else
            resumingContinuation?.resume(resumeResult!!)

        if (throwable != null)
            pendingContinuation?.resumeWithException(throwable)
        else
            pendingContinuation?.resume(pendingResult!!)

        if (data.throwable != null)
            throw data.throwable
        return resumeResult
    }
}
private data class BatonWaiter<T, R>(val continuation: Continuation<R>?, val data: BatonData<T, R>) {
    fun getContinuationLockedData(): BatonContinuationLockedData<T, R> {
        return BatonContinuationLockedData(continuation, data)
    }
}

class Baton<T>() {
    private val freeze = FreezableAtomicReference<BatonWaiter<T, *>>()

    private fun <R> passInternal(throwable: Throwable?, value: T?, lock: BatonTossLock<T, R>? = null, finish: Boolean = false, continuation: Continuation<R>? = null): R? {
        val cdata = if (finish) {
            val finishData = BatonData(throwable, value, lock)
            val found = freeze.freeze(BatonWaiter(null, finishData))
            if (found != null)
                found.value.getContinuationLockedData()
            else
                BatonContinuationLockedData(null, null)
        }
        else {
            val resume = freeze.swapNotNull()
            if (resume != null) {
                // fast path with no extra allocations in case there's a waiter
                resume.value.getContinuationLockedData()
            }
            else {
                // slow path with allocations and spin lock
                val waiter = freeze.swapNull(BatonWaiter(continuation, BatonData(throwable, value, lock)))
                if (waiter == null)
                    BatonContinuationLockedData(null, null)
                else
                    waiter.value.getContinuationLockedData()
            }
        }

        return cdata.runLockBlocks(throwable, value, lock, continuation == null, finish).resumeContinuations(throwable, continuation)
    }


    private suspend fun <R> pass(throwable: Throwable?, value: T?, lock: BatonLock<T, R>? = null, finish: Boolean = false): R = suspendCoroutine {
        val wrappedLock: BatonTossLock<T, R>? =
        if (lock != null) {
            { result ->
                lock(result!!)
            }
        }
        else {
            null
        }
        passInternal(throwable, value, wrappedLock, finish, it)
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

    private val defaultLock: BatonLock<T, T?> = { result ->
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
