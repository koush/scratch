package com.koushikdutta.scratch

import com.koushikdutta.scratch.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class Yielder {
    private val waiting = AtomicReference<Continuation<Unit>?>(null)
    private fun updateWaiting(update: Continuation<Unit>?): Continuation<Unit>? {
        return waiting.getAndSet(update)
    }
    fun resume() {
        updateWaiting(null)?.resume(Unit)
    }
    fun resumeWithException(exception: Throwable) {
        updateWaiting(null)?.resumeWithException(exception)
    }
    suspend fun yield() {
        suspendCoroutine<Unit> { continuation ->
            updateWaiting(continuation)?.resume(Unit)
        }
    }
}
