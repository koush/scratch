package com.koushikdutta.scratch

import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

data class BatonResult<T>(val value: T, val resumed: Boolean)
typealias BatonLock<T> = (result: Result<BatonResult<T>>) -> Unit
typealias BatonTossLock<T, R> = (result: Result<BatonResult<T>>?) -> R
private data class BatonData<T>(val throwable: Throwable?, val value: T?, val lock: BatonLock<T>?)
private data class BatonTossData<T, R>(val throwable: Throwable?, val value: T?, val lockResult: R?)
private data class BatonContinuationLockedData<T>(val continuation: Continuation<BatonResult<T>>?, val data: BatonData<T>?)
private data class BatonContinuationData<T, R>(val continuation: Continuation<BatonResult<T>>?, val data: BatonData<T>?, val lockResult: R?)
private data class BatonWaiter<T>(val continuation: Continuation<BatonResult<T>>?, val data: BatonData<T>)

class Baton<T> {
    private var batonWaiter: BatonWaiter<T>? = null
    private var finishData: BatonData<T>? = null

    private fun <R> passInternal(throwable: Throwable?, value: T?, lock: BatonLock<T>? = null, tossLock: BatonTossLock<T, R>? = null, finish: Boolean = false, continuation: Continuation<BatonResult<T>?>?): BatonTossData<T, R>? {
        val cdata = synchronized(this) {
            val cdata =
            if (finishData != null) {
                if (finish)
                    BatonContinuationLockedData(null, BatonData<T>(Exception("Baton already closed"), null, null))
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
                    BatonContinuationLockedData(resume.continuation, resume.data)
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

            val data = cdata.data
            val lockResult: R?
            if (data != null) {
                if (data.throwable == null) {
                    val batonResult = BatonResult(data.value!!, true)
                    val result = Result.success(batonResult)
                    lockResult = tossLock?.invoke(Result.success(batonResult))
                    lock?.invoke(result)
                }
                else {
                    lockResult = tossLock?.invoke(Result.failure(data.throwable))
                }
                if (throwable == null)
                    data.lock?.invoke(Result.success(BatonResult(value!!, false)))
                else
                    data.lock?.invoke(Result.failure(throwable))
            }
            else {
                lockResult = tossLock?.invoke(null)
            }

            BatonContinuationData(cdata.continuation, cdata.data, lockResult)
        }
        // resume outside of the synchronized lock
        val data = cdata.data
        if (data != null) {
            if (data.throwable != null)
                continuation?.resumeWithException(data.throwable)
            else
                continuation?.resume(BatonResult(data.value!!, true))

            val resume = cdata.continuation
            if (throwable != null)
                resume?.resumeWithException(throwable)
            else
                resume?.resume(BatonResult(value!!, false))
        }

        if (data != null)
            return BatonTossData(data.throwable, data.value, cdata.lockResult)
        else
            return BatonTossData(data?.throwable, data?.value, cdata.lockResult)
    }

    private fun <R> passInternalThrow(throwable: Throwable?, value: T?, lock: BatonLock<T>? = null, tossLock: BatonTossLock<T, R>? = null, finish: Boolean = false): BatonTossData<T, R>? {
        val data = passInternal(throwable, value, lock, tossLock, finish, null)
        if (data?.throwable != null)
            throw data.throwable
        return data
    }

    private suspend fun pass(throwable: Throwable?, value: T?, lock: BatonLock<T>? = null, finish: Boolean = false): BatonResult<T>? = suspendCoroutine {
        passInternal<Unit>(throwable, value, lock, null, finish, it)
    }

    fun rethrow() {
        if (finishData?.throwable != null)
            throw finishData!!.throwable!!
    }

    private val defaultTossLock: BatonTossLock<T, T?> = {
        if (it != null)
            it.getOrNull()?.value
        else
            null
    }

    fun toss(value: T): T? {
        return tossResult(value, defaultTossLock)
    }

    fun tossRaise(throwable: Throwable): T? {
        return tossRaiseResult(throwable, defaultTossLock)
    }

    fun <R> tossResult(value: T, tossLock: BatonTossLock<T, R>? = null): R {
        return passInternalThrow(null, value, tossLock = tossLock)?.lockResult!!
    }

    fun <R> tossRaiseResult(throwable: Throwable, tossLock: BatonTossLock<T, R>? = null): R {
        return passInternalThrow(throwable, null, tossLock = tossLock)?.lockResult!!
    }

    fun finish(value: T): T? {
        return passInternalThrow<Unit>(null, value, finish = true)?.value
    }

    fun raiseFinish(throwable: Throwable): T? {
        return passInternalThrow<Unit>(throwable, null, finish = true)?.value
    }

    suspend fun pass(value: T, lock: BatonLock<T>? = null): T {
        return pass(null, value, lock)!!.value
    }

    suspend fun passResult(value: T, lock: BatonLock<T>? = null): BatonResult<T> {
        return pass(null, value, lock)!!
    }

    suspend fun raise(throwable: Throwable, lock: BatonLock<T>? = null): T {
        return pass(throwable, null, lock)!!.value
    }

    suspend fun raiseResult(throwable: Throwable, lock: BatonLock<T>? = null): BatonResult<T> {
        return pass(throwable, null, lock)!!
    }
}
