package com.koushikdutta.scratch

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
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
}