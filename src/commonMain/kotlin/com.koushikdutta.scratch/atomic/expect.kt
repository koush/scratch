package com.koushikdutta.scratch.atomic

expect class AtomicBoolean(initialValue: Boolean) {
    constructor()
    fun get(): Boolean
    fun set(value: Boolean)
    fun getAndSet(value: Boolean): Boolean
}

expect class AtomicReference<V>(initialValue: V) {
    fun compareAndSet(expect: V, update: V): Boolean
    fun get(): V
    fun getAndSet(newValue: V): V
    fun set(value: V)
}

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
