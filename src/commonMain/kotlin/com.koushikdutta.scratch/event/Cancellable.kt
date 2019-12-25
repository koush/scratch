package com.koushikdutta.scratch.event

interface Cancellable {
    /**
     * Check whether this asynchronous operation completed successfully.
     * @return
     */
    val isDone: Boolean

    /**
     * Check whether this asynchronous operation has been cancelled.
     * @return
     */
    val isCancelled: Boolean

    /**
     * Attempt to cancel this asynchronous operation.
     * @return The return value is whether the operation cancelled successfully.
     */
    fun cancel(): Boolean

    companion object {
        val CANCELLED = object : Cancellable {
            override val isDone: Boolean = false
            override val isCancelled: Boolean = true
            override fun cancel(): Boolean {
                return true
            }
        }
    }
}
