package com.koushikdutta.scratch.atomic

data class FreezableAtomicData<T>(val frozen: Boolean, val value: T)

class FreezableAtomicSwapNull<T> {
    private val atomicReference = AtomicReference<FreezableAtomicData<T>?>(null)

    /**
     * Return the current value, replacing it with null if the value is not frozen.
     */
    fun swapNotNull(): FreezableAtomicData<T>? {
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
    fun swapNull(value: T): FreezableAtomicData<T>? {
        val freezable = FreezableAtomicData(false, value)
        // successfully set the value, expecting null will return null
        while (!atomicReference.compareAndSet(null, freezable)) {
            val ret = swapNotNull()
            if (ret != null)
                return ret
        }
        return null
    }

    fun freeze(value: T): FreezableAtomicData<T>? {
        val frozen = FreezableAtomicData(true, value)

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
