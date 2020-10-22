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

fun interface PromiseNextCallback<R, T> {
    @Throws(Throwable::class)
    fun next(result: T): Promise<R>
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
    fun resolve(result: Promise<T>) {
        GlobalScope.async(Dispatchers.Unconfined, start = result.start) {
            try {
                resolve(promise.await())
            }
            catch (throwable: Throwable) {
                reject(throwable)
            }
        }
    }

    companion object {
        @JvmStatic
        suspend fun <T> deferResolve(block: suspend (resolve: (T) -> Boolean) -> Unit): T {
            val deferred = Deferred<T>()
            block(deferred::resolve)
            return deferred.promise.await()
        }

        @JvmStatic
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

open class Promise<T> internal constructor(val deferred: kotlinx.coroutines.Deferred<T>, internal val start: CoroutineStart) {
    constructor(block: suspend () -> T) : this(suspendJob(block, CoroutineStart.DEFAULT), CoroutineStart.DEFAULT)
    constructor(start: CoroutineStart, block: suspend () -> T) : this(suspendJob(block, start), start)
    constructor(deferred: kotlinx.coroutines.Deferred<T>): this(deferred, CoroutineStart.DEFAULT)

    private val childStart = CompletableDeferred<Unit>()

    init {
        if (start != CoroutineStart.LAZY)
            childStart.complete(Unit)
    }

    fun cancel() = cancel(null)

    fun cancel(cause: CancellationException? = null): Boolean {
        deferred.cancel(cause)
        childStart.completeExceptionally(cause ?: CancellationException("parent promise cancelled"))
        return deferred.isCancelled
    }

    fun cancel(message: String, cause: Throwable? = null): Boolean {
        deferred.cancel(message, cause)
        childStart.completeExceptionally(cause ?: CancellationException("parent promise cancelled", cause))
        return deferred.isCancelled
    }

    fun start() {
        childStart.complete(Unit)
        deferred.start()
    }

    val isStarted: Boolean
        get() = deferred.isActive || deferred.isCancelled || deferred.isCompleted

    suspend fun await(): T {
        childStart.await()
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

    fun <R> then(callback: PromiseThen<R, T>) = Promise() {
        callback(await())
    }

    fun cancelled(callback: PromiseCancelled) = Promise() {
        try {
            await()
        }
        catch (throwable: CancellationException) {
            callback(throwable)
            throw throwable
        }
    }

    fun catch(callback: PromiseCatch) = Promise() {
        try {
            await()
        }
        catch (throwable: Throwable) {
            callback(throwable)
            throw throwable
        }
    }

    fun catchThen(callback: PromiseCatchThen<T>) = Promise() {
        try {
            await()
        } catch (throwable: Throwable) {
            return@Promise callback(throwable)
        }
    }

    fun finally(callback: suspend () -> Unit) = Promise() {
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

    fun <R> next(callback: PromiseNextCallback<R, T>): Promise<R> {
        return then {
            callback.next(it).await()
        }
    }

    fun complete(callback: PromiseCompleteCallback<T>) = Promise() {
        val value = try {
            Result.success(await())
        }
        catch (throwable: Throwable) {
            Result.failure(throwable)
        }
        callback.complete(value)
        value
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
