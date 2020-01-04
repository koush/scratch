package com.koushikdutta.scratch

import com.koushikdutta.scratch.async.startSafeCoroutine
import com.koushikdutta.scratch.atomic.AtomicReference
import com.koushikdutta.scratch.atomic.FreezableStack
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

typealias PromiseCallback<T, R> = suspend (T) -> R
typealias PromiseCatch<R> = suspend (throwable: Throwable) -> R

private interface PromiseResult<T> {
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

open class Promise<T> {
    private val atomicReference = AtomicReference<PromiseResult<T>?>(null)
    private val callbacks = FreezableStack<Continuation<T>, Unit>(Unit) { _, _ ->
        Unit
    }

    constructor(block: suspend () -> T) {
        startSafeCoroutine {
            val result =
            try {
                block()
            }
            catch (throwable: Throwable) {
                atomicReference.set(PromiseResult.failure(throwable))
                callbacks.freeze()
                callbacks.clear(Unit) { _, continuation ->
                    continuation.resumeWithException(throwable)
                }
                return@startSafeCoroutine
            }

            atomicReference.set(PromiseResult.success(result))
            callbacks.freeze()
            callbacks.clear(Unit) { _, continuation ->
                continuation.resume(result)
            }
        }
    }

    fun <R> then(callback: PromiseCallback<T, R>): Promise<R> {
        return Promise {
            val result = suspendCoroutine<T> {
                if (!callbacks.push(it).frozen)
                    return@suspendCoroutine
                try {
                    it.resume(atomicReference.get()!!.getOrThrow())
                }
                catch (throwable: Throwable) {
                    it.resumeWithException(throwable)
                }
            }
            callback(result)
        }
    }

    fun <R> catch(callback: PromiseCatch<R>): Promise<R> {
        return Promise {
            try {
                suspendCoroutine<T> {
                    if (!callbacks.push(it).frozen)
                        return@suspendCoroutine
                    try {
                        atomicReference.get()!!.getOrThrow()
                        // ignore result
                    }
                    catch (throwable: Throwable) {
                        it.resumeWithException(throwable)
                    }
                }
                throw AssertionError("promise catch reached valid result")
            }
            catch (throwable: Throwable) {
                callback(throwable)
            }
        }
    }

    fun <R> finally(callback: suspend () -> R): Promise<R> {
        return Promise {
            try {
                suspendCoroutine<T> {
                    if (!callbacks.push(it).frozen)
                        return@suspendCoroutine
                    try {
                        it.resume(atomicReference.get()!!.getOrThrow())
                    }
                    catch (throwable: Throwable) {
                        it.resumeWithException(throwable)
                    }
                }
                throw AssertionError("promise catch reached valid result")
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
                atomicReference.get()!!.getOrThrow()
            }
            catch (throwable: Throwable) {
                it.resumeWithException(throwable)
                return@suspendCoroutine
            }
            it.resume(result)
        }
    }

    fun rethrow() {
        atomicReference.get()?.getOrThrow()
    }

    fun getOrThrow() = atomicReference.get()?.getOrThrow()
}
