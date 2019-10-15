package com.koushikdutta.scratch.event

open class SimpleCancellable : Cancellable {
    internal var complete: Boolean = false
    internal var cancelled: Boolean = false
    override val isDone: Boolean
        get() = complete

    protected fun cancelCleanup() {}

    protected fun cleanup() {}

    protected fun completeCleanup() {}

    fun setComplete(): Boolean {
        synchronized(this) {
            if (cancelled)
                return false
            if (complete) {
                // don't allow a Cancellable to complete twice...
                return false
            }
            complete = true
        }
        completeCleanup()
        cleanup()
        return true
    }

    override fun cancel(): Boolean {
        synchronized(this) {
            if (complete)
                return false
            if (cancelled)
                return true
            cancelled = true

        }
        cancelCleanup()
        cleanup()
        return true
    }

    override val isCancelled: Boolean
        get() = cancelled

    fun reset(): Cancellable {
        cancel()
        complete = false
        cancelled = false
        return this
    }

    companion object {

        val COMPLETED: Cancellable = object : SimpleCancellable() {
            init {
                setComplete()
            }
        }

        val CANCELLED: Cancellable = object : SimpleCancellable() {
            init {
                this.cancel()
            }
        }
    }
}
