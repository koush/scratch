package com.koushikdutta.scratch

import java.util.concurrent.Semaphore

fun <T> Promise<T>.get(): T {
    val semaphore = Semaphore(0)
    complete {
        semaphore.release()
    }
    return getOrThrow()
}

class PromiseHelper {
    companion object {
        @JvmStatic
        fun <T> get(promise: Promise<T>): T {
            val semaphore = Semaphore(0)
            promise.complete {
                semaphore.release()
            }
            semaphore.acquire()
            return promise.getOrThrow()
        }
    }
}