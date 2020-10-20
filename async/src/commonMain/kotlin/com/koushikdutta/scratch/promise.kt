package com.koushikdutta.scratch

import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import kotlin.jvm.JvmStatic

typealias PromiseThen<R, T> = suspend (T) -> R
typealias PromiseCatch = suspend (throwable: Throwable) -> Unit
typealias PromiseCancelled = suspend (throwable: CancellationException) -> Unit
typealias PromiseCatchThen<T> = suspend (throwable: Throwable) -> T

fun interface PromiseApplyCallback<R, T> {
    @Throws(Throwable::class)
    fun apply(result: T): R
}

fun interface PromiseResultCallback<T> {
    @Throws(Throwable::class)
    fun result(result: T)
}

fun interface PromiseCompleteCallback<T> {
    fun complete(result: Result<T>)
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

internal fun <T> suspendJob(block: suspend () -> T, start: CoroutineStart) = GlobalScope.async(Dispatchers.Unconfined, start) {
    block()
}

fun <T> kotlinx.coroutines.Deferred<T>.asPromise(start: CoroutineStart = CoroutineStart.DEFAULT): Promise<T> = Promise(this, start)

open class Promise<T> constructor(private val deferred: kotlinx.coroutines.Deferred<T>, val start: CoroutineStart) {
    constructor(block: suspend () -> T) : this(suspendJob(block, CoroutineStart.DEFAULT), CoroutineStart.DEFAULT)
    constructor(start: CoroutineStart, block: suspend () -> T) : this(suspendJob(block, start), start)
    constructor(deferred: kotlinx.coroutines.Deferred<T>): this(deferred, CoroutineStart.DEFAULT)

    fun cancel() = cancel(null)

    fun cancel(cause: CancellationException? = null): Boolean {
        deferred.cancel(cause)
        return deferred.isCancelled
    }

    fun cancel(message: String, cause: Throwable? = null): Boolean {
        deferred.cancel(message, cause)
        return deferred.isCancelled
    }

    fun start() = deferred.start()

    val isStarted: Boolean
        get() = deferred.isActive || deferred.isCancelled || deferred.isCompleted

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

    fun <R> then(callback: PromiseThen<R, T>) = Promise(start) {
        callback(await())
    }

    fun cancelled(callback: PromiseCancelled) = Promise(start) {
        try {
            await()
        }
        catch (throwable: CancellationException) {
            callback(throwable)
            throw throwable
        }
    }

    fun catch(callback: PromiseCatch) = Promise(start) {
        try {
            await()
        }
        catch (throwable: Throwable) {
            callback(throwable)
            throw throwable
        }
    }

    fun catchThen(callback: PromiseCatchThen<T>) = Promise(start) {
        try {
            await()
        } catch (throwable: Throwable) {
            return@Promise callback(throwable)
        }
    }

    fun finally(callback: suspend () -> Unit) = Promise(start) {
        try {
            await()
        } finally {
            callback()
        }
    }

    fun result(callback: PromiseResultCallback<T>): Promise<T> {
        return then {
            callback.result(it)
            it
        }
    }

    fun complete(callback: PromiseCompleteCallback<T>) = Promise(start) {
        val value = try {
            await()
        }
        catch (throwable: Throwable) {
            callback.complete(Result.failure(throwable))
            return@Promise
        }
        callback.complete(Result.success(value))
    }

    fun <R> apply(callback: PromiseApplyCallback<R, T>): Promise<R> {
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
