package com.koushikdutta.scratch

import android.os.Handler
import android.os.Looper
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

suspend fun Looper.createAsyncAffinity(): AsyncAffinity {
    val handler = Handler(this)

    return object : AsyncAffinity {
        override suspend fun await() {
            if (thread == Thread.currentThread())
                return
            post()
            if (thread != Thread.currentThread()) {
                val err = "Failed to switch to affinity thread, how did this happen? Use Dispatchers.Unconfirmed when creating your coroutine. Or use AsyncEventLoop.async or AsyncEventLoop.launch."
                println(err)
                throw IllegalStateException(err)
            }
        }

        override suspend fun post() {
            // this special invocation forces the coroutine to resume on the scheduler thread.
            kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn { it: Continuation<Unit> ->
                handler.post {
                    it.resume(Unit)
                }
                kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
            }
        }
    }
}
