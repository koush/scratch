package com.koushikdutta.scratch.atomic

class FreezableStackNode<T, V>(internal val previous: FreezableStackNode<T, V>?, val value: T?, val frozen: Boolean, val size: Int, val accumulate: V)
class FreezableStack<T, V>(private val defaultAccumulate: V, private val accumulate: (accumulated: V, value: T) -> V):
    Freezable {
    private val atomicReference =
        AtomicReference<FreezableStackNode<T, V>?>(
            null
        )
    val accumulated: V
        get() =  atomicReference.get()?.accumulate ?: defaultAccumulate

    override val isFrozen: Boolean
        get() = atomicReference.get()?.frozen == true

    override val isImmutable: Boolean
        get() {
            val top = atomicReference.get()
            return top?.frozen == true && top.previous == null
        }

    fun push(value: T): FreezableStackNode<T, V> {
        while (true) {
            val top = atomicReference.get()
            if (top?.frozen == true)
                return top
            val node = FreezableStackNode(
                top,
                value,
                false,
                (top?.size ?: -1) + 1,
                accumulate(top?.accumulate ?: defaultAccumulate, value)
            )
            if (atomicReference.compareAndSet(top, node))
                return node
        }
    }

    fun <R> clearFreeze(initialValue: R, accumulate: (collector: R, value: T) -> R): R {
        // no need to compare and set. it enters an immutable state and is idempotent.
        var value = atomicReference.getAndSet(FreezableStackNode(null, null, true, 0, defaultAccumulate))
        var ret = initialValue
        while (value != null) {
            if (!value.frozen)
                ret = accumulate(ret, value.value!!)
            value = value.previous
        }
        return ret
    }

    fun freeze(): Boolean {
        while (true) {
            val top = atomicReference.get()
            if (top?.frozen == true)
                return false

            val newTop = if (top == null)
                FreezableStackNode<T, V>(
                    top,
                    null,
                    true,
                    0,
                    defaultAccumulate
                )
            else
                FreezableStackNode(
                    top,
                    null,
                    true,
                    0,
                    defaultAccumulate
                )

            if (atomicReference.compareAndSet(top, newTop))
                return true
        }
    }

    fun pop(): FreezableStackNode<T, V>? {
        while (true) {
            val top = atomicReference.get()
            if (top == null)
                return null

            if (top.frozen) {
                val underFrozenTop = top.previous
                if (underFrozenTop == null)
                    return top
                val newTop = FreezableStackNode(
                    underFrozenTop.previous,
                    null,
                    true,
                    0,
                    defaultAccumulate
                )
                if (atomicReference.compareAndSet(top, newTop))
                    return underFrozenTop
            }
            else {
                val newTop = top.previous
                if (atomicReference.compareAndSet(top, newTop))
                    return top
            }
        }
    }

    fun <R> clear(initialValue: R, accumulate: (collector: R, value: T) -> R): R {
        while (true) {
            val top = atomicReference.get()

            if (top == null || (top.frozen && top.previous == null))
                return initialValue

            if (top.frozen) {
                val underFrozenTop = top.previous
                val newTop = FreezableStackNode<T, V>(
                    null,
                    null,
                    true,
                    0,
                    defaultAccumulate
                )

                var ret = initialValue
                var value = underFrozenTop
                while (value != null) {
                    if (!value.frozen)
                        ret = accumulate(ret, value.value!!)
                    value = value.previous
                }
                if (atomicReference.compareAndSet(top, newTop))
                    return ret
            }
            else {
                var value = atomicReference.get()
                var ret = initialValue
                while (value != null) {
                    if (!value.frozen)
                        ret = accumulate(ret, value.value!!)
                    value = value.previous
                }
                if (atomicReference.compareAndSet(top, null))
                    return ret
            }
        }
    }
}