package com.koushikdutta.scratch

import com.koushikdutta.scratch.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

interface FutureScope {
    val isCancelled: Boolean
}

open class Future<T> internal constructor(private var cancelBlock: () -> Unit = {}) : Promise<T>(), Cancellable {
    internal open fun cancelInternal(resume: Boolean = true): Boolean {
        if (!reject(CancellationException(), resume))
            return false
        cancelBlock()
        return true
    }

    override fun cancel() = cancelInternal()
    fun cancelSilently() = cancelInternal(false)

    constructor(block: suspend FutureScope.() -> T): this() {
        val cancelled = AtomicBoolean()
        cancelBlock = {
            cancelled.set(true)
        }
        val scope = object : FutureScope {
            override val isCancelled
                get() = cancelled.get()
        }

        start {
            block(scope)
        }
    }

    constructor (cancelBlock: () -> Unit, block: suspend () -> T) : this(cancelBlock) {
        start(block)
    }

    override val isDone: Boolean
        get() = atomicReference.isFrozen

    override val isCancelled: Boolean
        get() {
            return try {
                rethrowIfDone()
                false
            } catch (throwable: CancellationException) {
                true
            } catch (throwable: Throwable) {
                false
            }
        }
}

class ChildFuture<T> : Future<T> {
    private var parent: Future<*>?

    override fun cancelInternal(resume: Boolean): Boolean {
        if (!super.cancelInternal(resume))
            return false
        parent?.cancel()
        parent = null
        return true
    }

    constructor(parent: Future<*>, block: suspend FutureScope.() -> T): super(block) {
        this.parent = parent
    }

    constructor(parent: Future<*>, cancelBlock: () -> Unit, block: suspend () -> T) : super(cancelBlock) {
        this.parent = parent
    }
}