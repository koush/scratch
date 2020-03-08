package com.koushikdutta.scratch.async

import com.koushikdutta.scratch.AsyncAffinity
import com.koushikdutta.scratch.AsyncQueue
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine


/**
 * Create a coroutine executor that can be used to serialize
 * suspending calls.
 */
class AsyncHandler(private val affinity: AsyncAffinity) {
    private val queue = AsyncQueue<suspend() -> Unit>()
    private var blocked = false
    init {
        startSafeCoroutine {
            for (block in queue) {
                affinity.await()
                runBlock(block)
            }
        }
    }

    fun post(block: suspend() -> Unit) {
        queue.add(block)
    }

    // run a block. requires that the call is on the affinity thread.
    private suspend fun <T> runBlock(block: suspend() -> T): T {
        blocked = true
        try {
            return block()
        }
        finally {
            blocked = false
        }
    }

    suspend fun <T> run(block: suspend() -> T): T {
        affinity.await()

        // fast(?) path in case there's nothing in the queue
        // unsure the cost of coroutines, but this prevents a queue/iterator/suspend hit.
        if (!blocked) {
            return runBlock(block)
        }

        return suspendCoroutine {
            post {
                val ret: T =
                        try {
                            block()
                        }
                        catch (t: Throwable) {
                            // fine to catch Throwables here because it is monitored
                            it.resumeWithException(t)
                            return@post
                        }
                it.resume(ret)
            }
        }
    }
}
