package com.koushikdutta.scratch.atomic

typealias AtomicReference<V> = java.util.concurrent.atomic.AtomicReference<V>
typealias AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean

/**
 * If the current value is null, swap the null with the provided value.
 * If the current value is not null, swap the current value with null.
 *
 * The current value is returned in both cases.
 */
fun <V> AtomicReference<V?>.swapIfNullElseNull(value: V): V? {
    // successfully set the value, expecting null will return null
    while (!compareAndSet(null, value)) {
        // value being held is currently not null, attempt to null it and retrieve it.
        val ret = getAndSet(null)
        // if the value returned is not null as expected, return it
        if (ret != null)
            return ret
        // otherwise retry the operation
    }
    return null
}
