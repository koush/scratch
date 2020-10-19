package com.koushikdutta.scratch

import kotlinx.coroutines.*
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExpectedBehaviorTests {
    @Test
    fun testLaunchAwaitOrder() {
        var launched = false
        var done = false
        GlobalScope.launch(Dispatchers.Unconfined) {
            val deferred = CompletableDeferred<Unit>()

            GlobalScope.launch(Dispatchers.Unconfined) {
                launched = true
                deferred.complete(Unit)
            }

            assertFalse(launched)
            deferred.await()
            assertTrue(launched)
            done = true
        }
        assertTrue(done)
    }

    @Test
    fun testLazyDeferredAwait() {
        var finished = false
        val deferred = GlobalScope.async(Dispatchers.Unconfined, start = CoroutineStart.LAZY) {
            finished = true
        }

        GlobalScope.launch(Dispatchers.Unconfined) {
            deferred.await()
        }

        assertTrue(finished)
    }
}
