package com.koushikdutta.scratch

import android.os.Handler
import android.os.Looper
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

fun Looper.createAsyncAffinity(): AsyncAffinity {
    return Handler(this).createAsyncAffinity()
}

fun Handler.createAsyncAffinity(): AsyncAffinity {
    val handler = this

    return object : AsyncAffinity {
        override suspend fun await() {
            if (handler.looper.thread == Thread.currentThread())
                return
            post()
            if (handler.looper.thread != Thread.currentThread()) {
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
