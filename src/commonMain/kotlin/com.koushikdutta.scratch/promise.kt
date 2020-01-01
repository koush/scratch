package com.koushikdutta.scratch

import com.koushikdutta.scratch.async.startSafeCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

typealias PromiseCallback<T> = (Promise<T>) -> Unit

open class Promise<T> {
    var exception: Throwable? = null
        internal set
    var done = false
        internal set
    var value: T? = null
        internal set
    private var callback: PromiseCallback<T>? = null

    constructor()
    constructor(block: suspend () -> T) {
        startSafeCoroutine {
            val result =
            try {
                block()
            }
            catch (throwable: Throwable) {
                setComplete(throwable, null)
                return@startSafeCoroutine
            }
            setComplete(null, result)
        }
    }

    fun setCallback(callback: PromiseCallback<T>): Promise<T> {
        val done = synchronized(this) {
            if (done)
                return@synchronized true
            this.callback = callback
            false
        }

        if (done)
            invokeCallback(callback)
        return this
    }

    private fun invokeCallback(callback: PromiseCallback<T>) {
        callback(this)
    }

    open fun onComplete() {
    }

    internal fun setComplete(exception: Throwable?, value: T?): Boolean {
        val callback: PromiseCallback<T>? = synchronized(this) {
            if (done)
                return false
            if (exception != null) {
                this.exception = exception
            } else {
                this.value = value
            }
            done = true
            val callback: PromiseCallback<T>? = this.callback
            this.callback = null
            callback
        }
        onComplete()
        if (callback != null)
            invokeCallback(callback)
        return true
    }

    fun setComplete(result: Result<T>) {
        setComplete(result.exceptionOrNull(), result.getOrNull())
    }

    fun rethrow() {
        if (exception != null)
            throw exception!!
    }

    suspend fun await(): T {
        synchronized(this) {
            if (done) {
                rethrow()
                return value!!
            }
        }

        return suspendCoroutine { continuation ->
            setCallback {
                if (it.exception != null)
                    continuation.resumeWithException(it.exception!!)
                else
                    continuation.resume(it.value!!)
            }
        }
    }
}
