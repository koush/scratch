package com.koushikdutta.scratch.atomic

class FreezableValue<T>(val frozen: Boolean, val value: T)

interface Freezable {
    val isFrozen: Boolean
    val isImmutable: Boolean
}

/**
 * A freezable atomic reference. Once frozen, the atomic reference can no longer be changed.
 */
class FreezableReference<T>: Freezable {
    private val atomicReference =
        AtomicReference<FreezableValue<T>?>(
            null
        )

    /**
     * Return the current value, replacing it with null if the value is not frozen.
     */
    fun nullSwap(): FreezableValue<T>? {
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

    override val isFrozen: Boolean
        get() = getFrozen() != null

    override val isImmutable: Boolean
        get() = isFrozen

    /**
     * If the current value is null, swap the null with the provided value. Returns null.
     * If the current value is not null, return the current value, replacing it with null if the value is not frozen.
     *
     * The current value is returned in both cases.
     */
    fun swapIfNullElseNull(value: T): FreezableValue<T>? {
        val freezable = FreezableValue(false, value)
        // successfully set the value, expecting null will return null
        while (!atomicReference.compareAndSet(null, freezable)) {
            // expecting an existing value, so attempt to null swap it.
            val ret = nullSwap()
            if (ret != null)
                return ret
        }
        return null
    }

    fun get(): FreezableValue<T>? {
        return atomicReference.get()
    }

    fun set(value: T): FreezableValue<T>? {
        return setInternal(value, false)
    }

    fun compareAndSet(existing: FreezableValue<T>?, value: T, freeze: Boolean = false): Boolean {
        if (existing?.frozen == true)
            return false
        return atomicReference.compareAndSet(existing,
            FreezableValue(freeze, value)
        )
    }

    fun compareAndSetNull(existing: FreezableValue<T>): Boolean {
        if (existing.frozen == true)
            return false
        return atomicReference.compareAndSet(existing, null)
    }

    private fun setInternal(value: T, freeze: Boolean): FreezableValue<T>? {
        val newValue = FreezableValue(freeze, value)

        while (true) {
            val existing = atomicReference.get()
            if (existing?.frozen == true)
                return existing

            if (atomicReference.compareAndSet(existing, newValue))
                return existing
        }
    }

    fun swap(value: T): FreezableValue<T>? {
        val newValue = FreezableValue(false, value)
        while (true) {
            val current = atomicReference.get()
            if (current?.frozen == true)
                return current
            if (atomicReference.compareAndSet(current, newValue))
                return current
        }
    }

    fun freeze(value: T): FreezableValue<T>? {
        return setInternal(value, true)
    }
}


