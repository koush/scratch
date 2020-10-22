package com.koushikdutta.scratch

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val warning = "This method blocks the thread and is not recommended. Use carefully."

@Deprecated(warning)
fun <T> Promise<T>.get(): T = PromiseHelper.get(this)

@Deprecated(warning)
fun <T> Promise<T>.get(time: Long, timeUnit: TimeUnit): T = PromiseHelper.get(this, time, timeUnit)

class PromiseHelper {
    companion object {
        @JvmStatic
        @Deprecated(warning)
        fun <T> get(promise: Promise<T>): T {
            val semaphore = Semaphore(0)
            promise.complete {
                semaphore.release()
            }
            semaphore.acquire()
            return promise.getOrThrow()
        }

        @JvmStatic
        @Deprecated(warning)
        fun <T> get(promise: Promise<T>, time: Long, timeUnit: TimeUnit): T {
            val semaphore = Semaphore(0)
            promise.complete {
                semaphore.release()
            }
            if (!semaphore.tryAcquire(time, timeUnit))
                throw TimeoutException()
            return promise.getOrThrow()
        }

    }
}