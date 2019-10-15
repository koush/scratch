package com.koushikdutta.scratch.net

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
}
