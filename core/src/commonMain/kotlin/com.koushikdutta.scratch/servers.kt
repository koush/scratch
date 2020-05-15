package com.koushikdutta.scratch

import com.koushikdutta.scratch.async.startSafeCoroutine

/**
 * AsyncServerSocket accepts incoming AsyncSocket clients.
 */
interface AsyncServerSocket<T: AsyncSocket> : AsyncAffinity {
    fun accept(): AsyncIterable<out T>
    suspend fun close()
    suspend fun close(throwable: Throwable)
}

interface AsyncServer {
    fun listen(server: AsyncServerSocket<*>): AsyncAcceptObserver<*>
}

class AsyncAcceptObserver<T: AsyncSocket> internal constructor(val serverSocket: AsyncServerSocket<T>) {
    private var observer: suspend AsyncAcceptObserver<T>.(socket: T, exception: Throwable?) -> Unit = { socket, throwable ->
        socket.close()
        if (throwable != null)
            serverSocket.close(throwable)
    }

    internal val deferred = Deferred<Unit>()
    suspend fun awaitClose() {
        deferred.promise.await()
    }

    fun observe(block: suspend AsyncAcceptObserver<T>.(socket: T, exception: Throwable?) -> Unit): AsyncAcceptObserver<T> {
        observer = block
        return this
    }

    fun observeIgnoreErrors(): AsyncAcceptObserver<T> {
        return observe { socket, _ ->
            socket.close()
        }
    }

    fun observeIgnoreErrorsLeaveOpen(): AsyncAcceptObserver<T> {
        return observe { _, _ ->
        }
    }

    internal suspend fun invokeObserver(socket: T, exception: Throwable?) {
        try {
            observer(socket, exception)
        }
        catch (throwable: Throwable) {
            serverSocket.post()
            throw throwable
        }
    }
}

fun <T: AsyncSocket> AsyncServerSocket<T>.acceptAsync(block: suspend T.() ->Unit): AsyncAcceptObserver<T> {
    val ret = AsyncAcceptObserver(this)
    startSafeCoroutine {
        try {
            for (socket in accept()) {
                startSafeCoroutine socketCoroutine@{
                    try {
                        block(socket)
                    }
                    catch (throwable: Throwable) {
                        ret.invokeObserver(socket, throwable)
                        return@socketCoroutine
                    }
                    ret.invokeObserver(socket, null)
                }
            }
        }
        catch (throwable: Throwable) {
            ret.deferred.reject(throwable)
            return@startSafeCoroutine
        }
        ret.deferred.resolve(Unit)
    }

    return ret
}
