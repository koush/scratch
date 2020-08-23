package com.koushikdutta.scratch

import kotlin.coroutines.Continuation
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.*

class FutureTests {
    @Test
    fun testIsCancelled() {
        var continuation: Continuation<Unit>? = null
        var finished = false
        val future = Future {
            suspendCoroutine<Unit> {
                continuation = it
            }
            assertTrue(isCancelled)
            finished = true
            3
        }

        assertTrue(future.cancel())
        continuation!!.resume(Unit)
        assertTrue(finished)
        assertTrue(future.isCancelled)
        try {
            future.getOrThrow()
            fail("Expected CancellationException")
        }
        catch (cancelled: CancellationException) {
        }
    }

    @Test
    fun testCancellationBlock() {
        var continuation: Continuation<Unit>? = null
        var isCancelled = false
        var finished = false
        val future = Future(cancelBlock = {
            isCancelled = true
        }) {
            suspendCoroutine<Unit> {
                continuation = it
            }
            finished = true
            3
        }

        assertTrue(future.cancel())
        assertTrue(isCancelled)
        continuation!!.resume(Unit)
        assertTrue(finished)
        assertTrue(future.isCancelled)
        try {
            future.getOrThrow()
            fail("Expected CancellationException")
        }
        catch (cancelled: CancellationException) {
        }
    }

    @Test
    fun testFinish() {
        var continuation: Continuation<Unit>? = null
        var isCancelled = false
        var finished = false
        val future = Future(cancelBlock = {
            isCancelled = true
        }) {
            suspendCoroutine<Unit> {
                continuation = it
            }
            finished = true
            3
        }

        continuation!!.resume(Unit)
        assertFalse(isCancelled)
        assertFalse(future.cancel())
        assertTrue(finished)
        assertFalse(future.isCancelled)
        assertEquals(3, future.getOrThrow())
    }
}
