package com.koushikdutta.scratch

import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

data class BatonResult<T>(val value: T, val resumed: Boolean)
typealias BatonLock<T> = (result: BatonResult<T>) -> Unit
typealias BatonTossLock<T> = (result: BatonResult<T>?) -> Unit
private class BatonData<T>(val throwable: Throwable?, val value: T?, val lock: BatonLock<T>?)

class Baton<T> {
    private var waiter: Continuation<BatonResult<T>>? = null
    private var waiting: BatonData<T>? = null
    private var finishData: BatonData<T>? = null

    private fun passInternal(throwable: Throwable?, value: T?, lock: BatonTossLock<T>? = null, finish: Boolean = false, continuation: Continuation<BatonResult<T>?>?): BatonData<T>? {
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
                        waiter = continuation
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
                continuation?.resumeWithException(data.throwable)
            else
                continuation?.resume(BatonResult(data.value!!, true))

            val resume = pair.first
            if (throwable != null)
                resume?.resumeWithException(throwable)
            else
                resume?.resume(BatonResult(value!!, false))
        }
        // dead code. finish should not pass a continuation. left here for consistency in case that changes back.
//        else if (finish) {
//            continuation?.resume(null)
//        }

        return data
    }

    private fun wrapLock(lock: BatonLock<T>?): BatonTossLock<T>? {
        if (lock == null)
            return null
        return {
            lock(it!!)
        }
    }

    private fun passInternalThrow(throwable: Throwable?, value: T?, lock: BatonLock<T>? = null, finish: Boolean = false): BatonData<T>? {
        val data = passInternal(throwable, value, wrapLock(lock), finish, null)
        if (data?.throwable != null)
            throw data.throwable
        return data
    }

    private suspend fun pass(throwable: Throwable?, value: T?, lock: BatonLock<T>? = null, finish: Boolean = false): BatonResult<T>? = suspendCoroutine {
        passInternal(throwable, value, wrapLock(lock), finish, it)
    }

    fun rethrow() {
        if (finishData?.throwable != null)
            throw finishData!!.throwable!!
    }

    fun toss(value: T, lock: BatonLock<T>? = null): T? {
        return passInternalThrow(null, value, lock = lock)?.value
    }

    fun tossRaise(throwable: Throwable, lock: BatonLock<T>? = null): T? {
        return passInternalThrow(throwable, null, lock = lock)?.value
    }

    fun finish(value: T): T? {
        return passInternalThrow(null, value, finish = true)?.value
    }

    fun raiseFinish(throwable: Throwable): T? {
        return passInternalThrow(throwable, null, finish = true)?.value
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
