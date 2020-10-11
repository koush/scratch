package com.koushikdutta.scratch

import com.koushikdutta.scratch.async.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlin.coroutines.*
import kotlin.test.*

class PromiseTests {
    @Test
    fun testPromise() {
        val promise = Promise {
            34
        }

        assertEquals(34, promise.getOrThrow())
        assertEquals(34, promise.getOrThrow())
    }

    @Test
    fun testPromiseResolveLater() {
        var continuation: Continuation<Int>? = null
        val promise = Promise<Int> {
            suspendCoroutine {
                continuation = it
            }
        }

        var callback1 = false
        promise.then {
            callback1 = true
        }

        var callback2 = false
        promise.then {
            callback2 = true
        }

        continuation!!.resume(34)

        assertTrue(callback1)
        assertTrue(callback2)
        assertEquals(34, promise.getOrThrow())
        assertEquals(34, promise.getOrThrow())
    }

    @Test
    fun testPromiseCatch() {
        val promise = Promise {
            34
        }

        try {
            promise.getOrThrow()
            fail("expected failure")
        }
        catch (throwable: Throwable) {
        }
        try {
            promise.getOrThrow()
            fail("expected failure")
        }
        catch (throwable: Throwable) {
        }
    }

    @Test
    fun testPromiseCatchResolveLater() {
        var continuation: Continuation<Int>? = null
        val promise = Promise<Int> {
            suspendCoroutine {
                continuation = it
            }
        }

        var callback1 = false
        promise.catch {
            callback1 = true
        }

        var callback2 = false
        promise.catch {
            callback2 = true
        }

        continuation!!.resumeWithException(Throwable())

        assertTrue(callback1)
        assertTrue(callback2)
    }


    @Test
    fun testPromiseChaining() {
        var threw = false
        val promise = Promise {
            "test"
        }
        .then<Int> {
            threw = true
            throw Throwable()
        }
        .catchThen {
            34
        }

        assertTrue(threw)
        assertEquals(34, promise.getOrThrow())
        assertEquals(34, promise.getOrThrow())
    }


    @Test
    fun testPromiseChainingResolveLater() {
        var threw = false
        var continuation: Continuation<String>? = null
        val promise = Promise<String> {
            suspendCoroutine {
                continuation = it
            }
        }
        .then<Int> {
            threw = true
            throw Throwable()
        }
        .catchThen {
            34
        }

        continuation!!.resume("test")

        assertTrue(threw)
        assertEquals(34, promise.getOrThrow())
        assertEquals(34, promise.getOrThrow())
    }

    @Test
    fun testPromiseChainingFinally() {
        var threw = false
        var continuation: Continuation<String>? = null
        var finalized = false
        val promise = Promise<String> {
            suspendCoroutine {
                continuation = it
            }
        }
        .then {
            threw = true
            throw Throwable()
        }
        .finally {
            finalized = true
        }

        continuation!!.resume("test")

        assertTrue(threw)
        assertTrue(finalized)
    }

    @Test
    fun testPromiseCancelHook() {
        var cancelled = false
        suspend {
            val d = Deferred<Unit>()

            val promise = d.promise.cancelled {
                cancelled = true
            }

            assertSame(d.promise, promise)

            promise.cancel()
            promise.await()
        }.startCoroutine(Continuation<Unit>(EmptyCoroutineContext) {
            Unit
        })

        assertTrue(cancelled)
    }

    @Test
    fun testKotlinDeferredBehavior() {
        val deferred = CompletableDeferred<Unit>()

        var finished = 0
        AsyncAffinity.NO_AFFINITY.async {
            deferred.await()
            finished++
        }

        AsyncAffinity.NO_AFFINITY.async {
            deferred.await()
            finished++
        }

        deferred.complete(Unit)
        assertEquals(finished, 2)
    }
}