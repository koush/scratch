package com.koushikdutta.scratch.atomic

data class FreezableAtomicReferenceValue<T>(val frozen: Boolean, val value: T)

/**
 * A freezable atomic reference. Once frozen, the atomic reference can no longer be changed.
 */
class FreezableAtomicReference<T> {
    private val atomicReference = AtomicReference<FreezableAtomicReferenceValue<T>?>(null)

    /**
     * Return the current value, replacing it with null if the value is not frozen.
     */
    fun swapNotNull(): FreezableAtomicReferenceValue<T>? {
        // check if frozen
        val freezeCheck = atomicReference.get()
        if (freezeCheck != null && freezeCheck.frozen)
            return freezeCheck

        // unfrozen value found, try swapping
        if (freezeCheck != null && atomicReference.compareAndSet(freezeCheck, null))
            return freezeCheck
        return null
    }

    fun getFrozen(): T? {
        val ret = atomicReference.get()
        if (ret != null && ret.frozen)
            return ret.value
        return null
    }

    val isFrozen: Boolean
        get() = getFrozen() != null

    /**
     * If the current value is null, swap the null with the provided value. Returns null.
     * If the current value is not null, return the current value, replacing it with null if the value is not frozen.
     *
     * The current value is returned in both cases.
     */
    fun swapNull(value: T): FreezableAtomicReferenceValue<T>? {
        val freezable = FreezableAtomicReferenceValue(false, value)
        // successfully set the value, expecting null will return null
        while (!atomicReference.compareAndSet(null, freezable)) {
            val ret = swapNotNull()
            if (ret != null)
                return ret
        }
        return null
    }

    fun get(): FreezableAtomicReferenceValue<T>? {
        return atomicReference.get()
    }

    fun swap(value: T): FreezableAtomicReferenceValue<T>? {
        val newValue = FreezableAtomicReferenceValue(false, value)
        while (true) {
            val current = atomicReference.get()
            if (current?.frozen == true)
                return current
            if (atomicReference.compareAndSet(current, newValue))
                return current
        }
    }

    fun freeze(value: T): FreezableAtomicReferenceValue<T>? {
        val frozen = FreezableAtomicReferenceValue(true, value)

        while (true) {
            // check if frozen
            val freezeCheck = atomicReference.get()
            if (freezeCheck != null && freezeCheck.frozen)
                return freezeCheck

            if (atomicReference.compareAndSet(freezeCheck, frozen))
                return freezeCheck
        }
    }
}

data class FreezableAtomicQueueNode<T>(val value: T, val frozen: Boolean) {
    internal val next = AtomicReference<FreezableAtomicQueueNode<T>?>(null)
}

/**
 * An atomic queue that can be frozen.
 * Once frozen, no more values may be added. Further values may be removed, until the queue is empty.
 */
class FreezableAtomicQueue<T> {
    private val tail = AtomicReference<FreezableAtomicQueueNode<T>?>(null)
    private val head = AtomicReference<FreezableAtomicQueueNode<T>?>(null)

    private fun addInternal(newTail: FreezableAtomicQueueNode<T>): Boolean {
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

    fun add(value: T) = addInternal(FreezableAtomicQueueNode(value, false))

    fun freeze(value: T) = addInternal(FreezableAtomicQueueNode(value, true))

    fun remove(): FreezableAtomicQueueNode<T>? {
        while (true) {
            val curHead = head.get()
            if (curHead == null || curHead.frozen)
                return curHead
            if (head.compareAndSet(curHead, curHead.next.get()))
                return curHead
        }
    }
}
