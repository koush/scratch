package com.koushikdutta.scratch

fun <T> Promise<T>.result(callback: JavaResultCallback<T>): Promise<T> {
    return then {
        callback.then(it)
        it
    }
}

fun <T, R> Promise<T>. apply(callback: JavaApplyCallback<T, R>): Promise<R> {
    return then {
        callback.apply(it).await()
    }
}

fun <T> Promise<T>.error(callback: JavaErrorCallback): Promise<T> {
    return catch {
        callback.error(it)
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
