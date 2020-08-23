package com.koushikdutta.scratch

actual open class Promise<T> : PromiseBase<T> {
    actual constructor(block: suspend () -> T): super(block)
    internal actual constructor(): super()

    fun result(callback: JavaResultCallback<T>): Promise<T> {
        return then {
            callback.then(it)
            it
        }
    }

    fun <R> apply(callback: JavaApplyCallback<T, R>): Promise<R> {
        return then {
            callback.apply(it).await()
        }
    }

    fun error(callback: JavaErrorCallback): Promise<T> {
        return catch {
            callback.error(it)
        }
    }
}

interface JavaApplyCallback<T, R> {
    @Throws(Throwable::class)
    fun apply(result: T): Promise<R>
}

interface JavaResultCallback<T> {
    @Throws(Throwable::class)
    fun then(result: T)
}

interface JavaErrorCallback {
    @Throws(Throwable::class)
    fun error(throwable: Throwable)
}
