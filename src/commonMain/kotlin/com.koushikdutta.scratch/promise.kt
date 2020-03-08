package com.koushikdutta.scratch

import com.koushikdutta.scratch.async.startSafeCoroutine
import com.koushikdutta.scratch.atomic.FreezableReference
import com.koushikdutta.scratch.atomic.FreezableStack
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

typealias PromiseCallback<T, R> = suspend (T) -> R
typealias PromiseCatch = suspend (throwable: Throwable) -> Unit
typealias PromiseRecover<T> = suspend (throwable: Throwable) -> T

internal interface PromiseResult<T> {
    fun getOrThrow(): T
    companion object {
        fun <T> failure(throwable: Throwable): PromiseResult<T> {
            return object : PromiseResult<T> {
                override fun getOrThrow(): T {
                    throw throwable
                }
            }
        }

        fun <T> success(value: T): PromiseResult<T> {
            return object : PromiseResult<T> {
                override fun getOrThrow(): T {
                    return value
                }
            }
        }
    }
}

class Deferred<T> {
    val promise = Promise<T>()
    fun resolve(result: T) = promise.resolve(result)
    fun reject(throwable: Throwable) = promise.reject(throwable)

    companion object {
        suspend fun <T> defer(block: suspend (resolve: (T) -> Boolean, reject: (Throwable) -> Boolean) -> Unit): T {
            val deferred = Deferred<T>()
            block(deferred::resolve, deferred::reject)
            return deferred.promise.await()
        }
    }
}

expect class Promise<T> : PromiseBase<T> {
    constructor(block: suspend () -> T)
    internal constructor()
}

open class PromiseBase<T> {
    internal val atomicReference = FreezableReference<PromiseResult<T>>()
    private val callbacks = FreezableStack<Continuation<T>, Unit>(Unit) { _, _ ->
        Unit
    }

    internal constructor()

    internal fun reject(throwable: Throwable): Boolean {
        if (atomicReference.freeze(PromiseResult.failure(throwable))?.frozen == true)
            return false
        callbacks.freeze()
        callbacks.clear(Unit) { _, continuation ->
            continuation.resumeWithException(throwable)
        }
        return true
    }

    internal fun resolve(result: T): Boolean {
        if (atomicReference.freeze(PromiseResult.success(result))?.frozen == true)
            return false
        callbacks.freeze()
        callbacks.clear(Unit) { _, continuation ->
            continuation.resume(result)
        }
        return true
    }

    constructor(block: suspend () -> T) {
        startSafeCoroutine {
            val result =
            try {
                block()
            }
            catch (throwable: Throwable) {
                reject(throwable)
                return@startSafeCoroutine
            }

            resolve(result)
        }
    }

    fun <R> then(callback: PromiseCallback<T, R>): Promise<R> {
        return Promise {
            val result = suspendCoroutine<T> {
                if (!callbacks.push(it).frozen)
                    return@suspendCoroutine
                try {
                    it.resume(atomicReference.get()!!.value.getOrThrow())
                }
                catch (throwable: Throwable) {
                    it.resumeWithException(throwable)
                }
            }
            callback(result)
        }
    }

    fun catch(callback: PromiseCatch): Promise<T> {
        return Promise {
            try {
                suspendCoroutine<T> {
                    if (!callbacks.push(it).frozen)
                        return@suspendCoroutine
                    try {
                        atomicReference.get()!!.value.getOrThrow()
                        return@suspendCoroutine
                        // ignore result
                    }
                    catch (throwable: Throwable) {
                        it.resumeWithException(throwable)
                    }
                }
            }
            catch (throwable: Throwable) {
                callback(throwable)
                throw throwable
            }
        }
    }

    fun recover(callback: PromiseRecover<T>): Promise<T> {
        return Promise {
            try {
                suspendCoroutine<T> {
                    if (!callbacks.push(it).frozen)
                        return@suspendCoroutine
                    try {
                        atomicReference.get()!!.value.getOrThrow()
                        return@suspendCoroutine
                        // ignore result
                    }
                    catch (throwable: Throwable) {
                        it.resumeWithException(throwable)
                    }
                }
            }
            catch (throwable: Throwable) {
                return@Promise callback(throwable)
            }
        }
    }

    fun finally(callback: suspend () -> Unit): Promise<T> {
        return Promise {
            try {
                suspendCoroutine<T> {
                    if (!callbacks.push(it).frozen)
                        return@suspendCoroutine
                    try {
                        it.resume(atomicReference.get()!!.value.getOrThrow())
                    }
                    catch (throwable: Throwable) {
                        it.resumeWithException(throwable)
                    }
                }
            }
            finally {
                callback()
            }
        }
    }

    suspend fun await(): T {
        return suspendCoroutine<T> {
            if (!callbacks.push(it).frozen)
                return@suspendCoroutine
            val result = try {
                atomicReference.get()!!.value.getOrThrow()
            }
            catch (throwable: Throwable) {
                it.resumeWithException(throwable)
                return@suspendCoroutine
            }
            it.resume(result)
        }
    }

    fun rethrow() {
        atomicReference.get()?.value?.getOrThrow()
    }

    fun getOrThrow() = atomicReference.get()!!.value.getOrThrow()
}
