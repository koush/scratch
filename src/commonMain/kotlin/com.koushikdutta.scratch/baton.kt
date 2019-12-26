package com.koushikdutta.scratch

import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

data class BatonResult<T>(val throwable: Throwable?, val value: T?, val resumed: Boolean)  {
    val isSuccess = throwable == null
    val isFailure = throwable != null
    fun rethrow() {
        if (throwable != null)
            throw throwable
    }
}
typealias BatonLock<T, R> = (result: BatonResult<T>) -> R
typealias BatonTossLock<T, R> = (result: BatonResult<T>?) -> R
private data class BatonData<T, R>(val throwable: Throwable?, val value: T?, val lock: BatonLock<T, R>?)
private data class BatonContinuationLockedData<T, RR>(val continuation: Continuation<RR>?, val data: BatonData<T, RR>?) {
    fun <R> runLockBlocks(throwable: Throwable?, value: T?, lock: BatonLock<T, R>?, tossLock: BatonTossLock<T, R>?): BatonContinuationData<T, R, RR> {
        val cdata = this
        val data = cdata.data
        val tossResult: R?
        val resumeResult: R?
        val pendingResult: RR?
        // a toss may not have a pending result.
        if (data != null) {
            val batonResult = BatonResult(data.throwable, data.value, true)
            tossResult = tossLock?.invoke(batonResult)
            resumeResult = lock?.invoke(batonResult)
            pendingResult = data.lock?.invoke(BatonResult(throwable, value, false))
        }
        else {
            tossResult = tossLock?.invoke(null)
            resumeResult = null
            pendingResult = null
        }

        return BatonContinuationData(cdata.continuation, cdata.data, pendingResult, resumeResult, tossResult)
    }
}

private data class BatonContinuationData<T, R, RR>(val pendingContinuation: Continuation<RR>?, val data: BatonData<T, RR>?, val pendingResult: RR?, val resumeResult: R?, val tossResult: R?) {
    fun resumeContinuations(throwable: Throwable?, resumingContinuation: Continuation<R>?): R? {
        if (data == null)
            return tossResult

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
        return tossResult
    }
}
private data class BatonWaiter<T, R>(val continuation: Continuation<R>?, val data: BatonData<T, R>) {
    fun getContinuationLockedData(): BatonContinuationLockedData<T, R> {
        return BatonContinuationLockedData(continuation, data)
    }
}

class Baton<T> {
    private var batonWaiter: BatonWaiter<T, *>? = null
    private var finishData: BatonData<T, *>? = null

    private fun <R> passInternal(throwable: Throwable?, value: T?, lock: BatonLock<T, R>? = null, tossLock: BatonTossLock<T, R>? = null, finish: Boolean = false, continuation: Continuation<R>? = null): R? {
        val cdata = synchronized(this) {
            val cdata =
            if (finishData != null) {
                if (finish)
                    BatonContinuationLockedData(null, BatonData<T, R>(Exception("Baton already closed"), null, null))
                else
                    BatonContinuationLockedData(null, finishData)
            }
            else if (batonWaiter != null) {
                // value already available
                val resume = batonWaiter!!
                batonWaiter = null
                if (finish) {
                    finishData = BatonData(throwable, value, lock)
                    BatonContinuationLockedData(resume.continuation, null)
                }
                else {
                    resume.getContinuationLockedData()
                }
            }
            else if (finish) {
                finishData = BatonData(throwable, value, lock)
                BatonContinuationLockedData(null, null)
            }
            else {
                // need to wait for value
                batonWaiter = BatonWaiter(continuation, BatonData(throwable, value, lock))
                BatonContinuationLockedData(null, null)
            }

            cdata.runLockBlocks(throwable, value, lock, tossLock)
        }

        return cdata.resumeContinuations(throwable, continuation)
    }

    private suspend fun <R> pass(throwable: Throwable?, value: T?, lock: BatonLock<T, R>? = null, finish: Boolean = false): R = suspendCoroutine {
        passInternal(throwable, value, lock, null, finish, it)
    }

    fun rethrow() {
        if (finishData?.throwable != null)
            throw finishData!!.throwable!!
    }

    private val defaultTossLock: BatonTossLock<T, T?> = {
        it?.value
    }

    fun toss(value: T): T? {
        return passInternal(null, value, tossLock = defaultTossLock)
    }

    fun tossRaise(throwable: Throwable): T? {
        return passInternal(throwable, null, tossLock = defaultTossLock)
    }

    fun <R> tossResult(value: T, tossLock: BatonTossLock<T, R>): R {
        return passInternal(null, value, tossLock = tossLock)!!
    }

    fun <R> tossRaiseResult(throwable: Throwable, tossLock: BatonTossLock<T, R>): R {
        return passInternal(throwable, null, tossLock = tossLock)!!
    }

    fun finish(value: T): T? {
        return passInternal(null, value, finish = true, tossLock = defaultTossLock)
    }

    fun raiseFinish(throwable: Throwable): T? {
        return passInternal(throwable, null, finish = true, tossLock = defaultTossLock)
    }

    private val defaultLock: BatonLock<T, T?> = { result ->
        result.value
    }

    suspend fun pass(value: T): T {
        return pass(null, value, defaultLock)!!
    }

    suspend fun <R> passResult(value: T, lock: BatonLock<T, R>): R {
        return pass(null, value, lock)!!
    }

    suspend fun raise(throwable: Throwable): T {
        return pass(throwable, null, defaultLock)!!
    }

    suspend fun <R> raiseResult(throwable: Throwable, lock: BatonLock<T, R>): R {
        return pass(throwable, null, lock)!!
    }
}
