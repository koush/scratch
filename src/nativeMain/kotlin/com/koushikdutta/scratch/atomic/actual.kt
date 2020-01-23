package com.koushikdutta.scratch.atomic

actual class AtomicBoolean actual constructor(initialValue: Boolean) {
    private var currentValue: Boolean = initialValue
    actual constructor() : this(false)
    actual fun get(): Boolean {
        return currentValue
    }
    actual fun set(value: Boolean) {
        currentValue = value
    }
    actual fun getAndSet(newValue: Boolean): Boolean {
        val oldValue = currentValue
        currentValue = newValue
        return oldValue
    }
    actual fun compareAndSet(expect: Boolean, update: Boolean): Boolean {
        if (currentValue != expect)
            return false
        currentValue = update
        return true
    }
}

actual class AtomicReference<V> actual constructor(initialValue: V) {
    private var currentValue: V = initialValue
    actual fun get(): V {
        return currentValue
    }
    actual fun set(value: V) {
        currentValue = value
    }
    actual fun getAndSet(newValue: V): V {
        val oldValue = currentValue
        currentValue = newValue
        return oldValue
    }
    actual fun compareAndSet(expect: V, update: V): Boolean {
        if (currentValue != expect)
            return false
        currentValue = update
        return true
    }
}
