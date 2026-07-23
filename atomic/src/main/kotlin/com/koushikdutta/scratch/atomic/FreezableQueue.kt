package com.koushikdutta.scratch.atomic

class FreezableQueueNode<T>(val value: T?, val frozen: Boolean) {
    internal val next =
        AtomicReference<FreezableQueueNode<T>?>(
            null
        )
}

/**
 * An atomic queue that can be frozen.
 * Once frozen, no more values may be added. Further values may be removed, until the queue is empty.
 */
class FreezableQueue<T>: Freezable {
    private val tail =
        AtomicReference<FreezableQueueNode<T>?>(
            null
        )
    private val head =
        AtomicReference<FreezableQueueNode<T>?>(
            null
        )

    private fun addInternal(newTail: FreezableQueueNode<T>): Boolean {
        while (true) {
            val curTail = tail.get()
            if (curTail?.frozen == true)
                return false
            if (curTail?.next?.compareAndSet(null, newTail) != false) {
                // this block is guarded and other writers will spin until tail is updated.

                // thus, set the new head first. removing items from the list is still atomic,
                // as only the head is updated.
                head.compareAndSet(null, newTail)

                tail.compareAndSet(curTail, newTail)
                return true
            }
        }
    }

    override val isFrozen: Boolean
        get() = tail.get()?.frozen == true

    override val isImmutable: Boolean
        get() = head.get()?.frozen == true

    fun add(value: T) = addInternal(FreezableQueueNode(value, false))

    fun freeze() = addInternal(FreezableQueueNode(null, true))

    fun remove(): FreezableQueueNode<T>? {
        while (true) {
            val curHead = head.get()
            if (curHead == null || curHead.frozen)
                return curHead
            if (head.compareAndSet(curHead, curHead.next.get()))
                return curHead
        }
    }
}