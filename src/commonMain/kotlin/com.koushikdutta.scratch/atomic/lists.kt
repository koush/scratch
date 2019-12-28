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

class BasicAtomicStack<T> : AtomicStack<T, Unit>(Unit, { _, _ ->  Unit })

class AtomicQueueNode<T>(internal val next: AtomicQueueNode<T>?, val value: T) {
    private val tail: AtomicQueueNode<T>
    val size: Int

    init {
        this.tail = next?.tail ?: this
        if (next == null)
            size = 0
        else
            size = next.size + 1
    }
}
open class AtomicQueue<T> {
    private val atomicReference = AtomicReference<AtomicQueueNode<T>?>(null)
    fun add(value: T) {
        while (true) {
            val head = atomicReference.get()
            val newHead = AtomicQueueNode(head, value)
            if (atomicReference.compareAndSet(head, newHead))
                break
        }
    }

    fun remove(): AtomicQueueNode<T>? {
        while (true) {
            val head = atomicReference.get()
            if (head == null)
                return null
            val newHead = head.next
            if (atomicReference.compareAndSet(head, newHead))
                return head
        }
    }
}