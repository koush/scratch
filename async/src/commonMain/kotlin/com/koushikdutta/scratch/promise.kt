package com.koushikdutta.scratch

import com.koushikdutta.scratch.atomic.FreezableReference
import com.koushikdutta.scratch.atomic.FreezableStack
import kotlin.coroutines.*

typealias PromiseThen<T, R> = suspend (T) -> R
typealias PromiseCatch = suspend (throwable: Throwable) -> Unit
typealias PromiseRecover<T> = suspend (throwable: Throwable) -> T

internal interface PromiseResult<T> {
    fun resume(resume: Continuation<T>)
    fun getOrThrow(): T
    companion object {
        fun <T> failure(throwable: Throwable): PromiseResult<T> {
            return object : PromiseResult<T> {
                override fun getOrThrow(): T {
                    throw throwable
                }
                override fun resume(resume: Continuation<T>) {
                    resume.resumeWithException(throwable)
                }
            }
        }

        fun <T> success(value: T): PromiseResult<T> {
            return object : PromiseResult<T> {
                override fun getOrThrow(): T {
                    return value
                }
                override fun resume(resume: Continuation<T>) {
                    resume.resume(value)
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
    private val atomicReference =
        FreezableReference<PromiseResult<T>>()
    private val callbacks =
        FreezableStack<Continuation<T>, Unit>(Unit) { _, _ ->
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
        block.startCoroutine(Continuation(EmptyCoroutineContext) {
            try {
                if (it.isFailure)
                    reject(it.exceptionOrNull()!!)
                else
                    resolve(it.getOrThrow())
            }
            catch (throwable: Throwable) {
                println("unexpected throwable in promise?")
                println(throwable)
                reject(throwable)
            }
        })
    }

    fun <R> then(callback: PromiseThen<T, R>): Promise<R> {
        return Promise {
            callback(await())
        }
    }

    fun catch(callback: PromiseCatch): Promise<T> {
        return Promise {
            try {
                await()
            } catch (throwable: Throwable) {
                callback(throwable)
                throw throwable
            }
        }
    }

    fun recover(callback: PromiseRecover<T>): Promise<T> {
        return Promise {
            try {
                await()
            } catch (throwable: Throwable) {
                return@Promise callback(throwable)
            }
        }
    }

    fun finally(callback: suspend () -> Unit): Promise<T> {
        return Promise {
            try {
                await()
            } finally {
                callback()
            }
        }
    }

    suspend fun await(): T {
        return suspendCoroutine {
            if (!callbacks.push(it).frozen)
                return@suspendCoroutine
            atomicReference.get()!!.value.resume(it)
        }
    }

    fun rethrow() {
        atomicReference.get()?.value?.getOrThrow()
    }

    fun getOrThrow() = atomicReference.get()!!.value.getOrThrow()
}
