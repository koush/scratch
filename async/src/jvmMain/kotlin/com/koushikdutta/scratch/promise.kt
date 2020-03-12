package com.koushikdutta.scratch

actual open class Promise<T> : PromiseBase<T> {
    actual constructor(block: suspend () -> T): super(block)
    internal actual constructor(): super()

    fun setCallback(callback: JavaThenCallback<T>): Promise<T> {
        return then {
            callback.then(it)
            it
        }
    }

    fun setErrorCallback(callback: JavaErrorCallback): Promise<T> {
        return catch {
            callback.error(it)
        }
    }
}

interface JavaThenCallback<T> {
    @Throws(Throwable::class)
    fun then(result: T)
}

interface JavaErrorCallback {
    @Throws(Throwable::class)
    fun error(throwable: Throwable)
}
