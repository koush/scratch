package com.koushikdutta.scratch

import com.koushikdutta.scratch.atomic.FreezableReference
import com.koushikdutta.scratch.atomic.FreezableStack
import kotlinx.coroutines.*
import kotlin.coroutines.*
import kotlin.jvm.JvmStatic

typealias PromiseThen<T, R> = suspend (T) -> R
typealias PromiseCatch = suspend (throwable: Throwable) -> Unit
typealias PromiseCancelled = suspend (throwable: CancellationException) -> Unit
typealias PromiseCatchThen<T> = suspend (throwable: Throwable) -> T

fun interface PromiseApplyCallback<T, R> {
    @Throws(Throwable::class)
    fun apply(result: T): R
}

fun interface PromiseThenCallback<T, R> {
    @Throws(Throwable::class)
    fun then(result: T): Promise<R>
}

fun interface PromiseResultCallback<T> {
    @Throws(Throwable::class)
    fun result(result: T)
}

fun interface PromiseErrorCallback {
    @Throws(Throwable::class)
    fun error(throwable: Throwable)
}

class Deferred<T> {
    val deferred = CompletableDeferred<T>()
    val promise = Promise(deferred)
    fun resolve(result: T) = deferred.complete(result)
    fun reject(throwable: Throwable) = deferred.completeExceptionally(throwable)

    companion object {
        suspend fun <T> deferResolve(block: suspend (resolve: (T) -> Boolean) -> Unit): T {
            val deferred = Deferred<T>()
            block(deferred::resolve)
            return deferred.promise.await()
        }

        suspend fun <T> defer(block: suspend (resolve: (T) -> Boolean, reject: (Throwable) -> Boolean) -> Unit): T {
            val deferred = Deferred<T>()
            block(deferred::resolve, deferred::reject)
            return deferred.promise.await()
        }
    }
}

internal fun <T> suspendJob(block: suspend () -> T) = GlobalScope.async(Dispatchers.Unconfined) {
    block()
}

fun <T> kotlinx.coroutines.Deferred<T>.asPromise(): Promise<T> = Promise(this)

open class Promise<T> constructor(private val deferred: kotlinx.coroutines.Deferred<T>) {
    internal val result = FreezableReference<Result<T>>()
    internal val callbacks =
            FreezableStack<Continuation<T>, Unit>(Unit) { _, _ ->
                Unit
            }

//    private val deferred: kotlinx.coroutines.Deferred<T>

//    init {
//        deferred = GlobalScope.async(Dispatchers.Unconfined) {
//            try {
//                result.freeze(Result.success(wrappedDeferred.await()))
//            } catch (throwable: Throwable) {
//                result.freeze(Result.failure(throwable))
//            }
//            val result = result.getFrozen()!!
//            callbacks.clearFreeze(Unit) { _, continuation ->
//                result.resume(continuation)
//            }
//            result.getOrThrow()
//        }
//    }

    constructor(block: suspend () -> T) : this(suspendJob(block))

    fun cancel() = cancel(null)

    fun cancel(cause: CancellationException? = null): Boolean {
        deferred.cancel(cause)
        return deferred.isCancelled
//        wrappedDeferred.cancel(cause)
//        return wrappedDeferred.isCancelled
    }

    fun cancel(message: String, cause: Throwable? = null): Boolean {
        deferred.cancel(message, cause)
        return deferred.isCancelled
//        wrappedDeferred.cancel(message, cause)
//        return wrappedDeferred.isCancelled
    }

    suspend fun await(): T {
        return deferred.await()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getOrThrow(): T {
        return deferred.getCompleted()
    }

    // just an alias that returns nothing.
    fun rethrow() {
        getOrThrow()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun rethrowIfDone() {
        if (deferred.isCompleted)
            deferred.getCompleted()
    }

    val isCompleted: Boolean
        get() = deferred.isCompleted

    val isCancelled: Boolean
        get() = deferred.isCancelled

    companion object {
        suspend fun getJob(): Job? {
            return coroutineContext[Job]
        }

        @JvmStatic
        fun <T> resolve(value: T): Promise<T> {
            return Promise(CompletableDeferred(value))
        }

        @JvmStatic
        fun <T> reject(throwable: Throwable): Promise<T> {
            val deferred = CompletableDeferred<T>()
            deferred.completeExceptionally(throwable)
            return Promise(deferred)
        }

        suspend fun ensureActive() {
            getJob()?.ensureActive()
        }
    }

    fun <R> then(callback: PromiseThen<T, R>) = Promise {
        callback(await())
    }

    fun cancelled(callback: PromiseCancelled): Promise<T> {
        Promise {
            try {
                await()
            } catch (throwable: CancellationException) {
                callback(throwable)
                throw throwable
            }
        }
        return this
    }

    fun catch(callback: PromiseCatch): Promise<T> {
        Promise {
            try {
                await()
            } catch (throwable: Throwable) {
                callback(throwable)
                throw throwable
            }
        }
        return this
    }

    fun catchThen(callback: PromiseCatchThen<T>) = Promise {
        try {
            await()
        } catch (throwable: Throwable) {
            return@Promise callback(throwable)
        }
    }

    fun finally(callback: suspend () -> Unit): Promise<T> {
        Promise {
            try {
                await()
            } finally {
                callback()
            }
        }
        return this
    }

    fun result(callback: PromiseResultCallback<T>): Promise<T> {
        return then {
            callback.result(it)
            it
        }
    }

    fun <R> then(callback: PromiseThenCallback<T, R>): Promise<R> {
        return then {
            callback.then(it).await()
        }
    }

    fun <R> apply(callback: PromiseApplyCallback<T, R>): Promise<R> {
        return then {
            callback.apply(it)
        }
    }

    fun error(callback: PromiseErrorCallback): Promise<T> {
        return catch {
            callback.error(it)
        }
    }
}
