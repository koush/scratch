package com.koushikdutta.scratch.atomic

class AtomicThrowingLock(inline val locked: () -> Throwable) {
    val atomic = AtomicBoolean(false)

    suspend inline operator fun <R> invoke(block: suspend() -> R): R {
        if (atomic.getAndSet(true))
            throw locked()
        try {
            return block()
        }
        finally {
            atomic.set(false)
        }
    }
}
