package com.koushikdutta.scratch.atomic

data class AtomicStackNode<T, V>(internal val previous: AtomicStackNode<T, V>?, val value: T, val size: Int, val accumulate: V)

open class AtomicStack<T, V>(val defaultAccumulate: V, val accumulate: (accumulated: V, value: T) -> V) {
    private val atomicReference = AtomicReference<AtomicStackNode<T, V>?>(null)
    val accumulated: V
        get() =  atomicReference.get()?.accumulate ?: defaultAccumulate

    fun push(value: T): V {
        while (true) {
            val top = atomicReference.get()
            val node =
            if (top != null)
                AtomicStackNode(top, value, top.size + 1, accumulate(top.accumulate, value))
            else
                AtomicStackNode(null, value, 0, accumulate(defaultAccumulate, value))
            if (atomicReference.compareAndSet(top, node))
                return node.accumulate
        }
    }

    fun pop(): AtomicStackNode<T, V>? {
        while (true) {
            val node = atomicReference.get()
            if (node == null)
                return null
            val top = node.previous
            if (atomicReference.compareAndSet(node, top))
                return node
        }
    }

    fun clear() {
        atomicReference.set(null)
    }

    fun clear(accumulate: (collector: T, value: T) -> T): T? {
        var value = atomicReference.getAndSet(null)
        var ret: T? = null
        while (value != null) {
            ret = if (ret != null)
                accumulate(ret, value.value)
            else
                value.value
            value = value.previous
        }
        return ret
    }
}

data class AtomicQueueNode<T>(val value: T) {
    internal val next = AtomicReference<AtomicQueueNode<T>?>(null)
}

open class AtomicQueue<T> {
    private val tail = AtomicReference<AtomicQueueNode<T>?>(null)
    private val head = AtomicReference<AtomicQueueNode<T>?>(null)

    fun add(value: T) {
        val newTail = AtomicQueueNode(value)
        while (true) {
            val curTail = tail.get()
            if (curTail?.next?.compareAndSet(null, newTail) != false) {
                // this block is guarded and other writers will spin until tail is updated.

                // thus, set the new head first. removing items from the list is still atomic,
                // as only the head is updated.
                head.compareAndSet(null, newTail)

                tail.compareAndSet(curTail, newTail)
                break
            }
        }
    }

    fun remove(): AtomicQueueNode<T>? {
        while (true) {
            val curHead = head.get()
            if (curHead == null)
                return curHead
            if (head.compareAndSet(curHead, curHead.next.get()))
                return curHead
        }
    }

    fun push(value: T) {
        val newHead = AtomicQueueNode(value)
        while (true) {
            val curHead = head.get()
            newHead.next.set(curHead)
            if (head.compareAndSet(curHead, newHead))
                break
        }
    }
}
