package com.koushikdutta.scratch

/**
 * AsyncServerSocket accepts incoming AsyncSocket clients.
 */
interface AsyncServerSocket<T: AsyncSocket> : AsyncAffinity {
    fun accept(): AsyncIterable<out T>
    suspend fun close()
}

class AsyncAcceptObserver<T: AsyncSocket> internal constructor(internal val serverSocket: AsyncServerSocket<T>): AsyncAffinity by serverSocket {
    private val unhandled = "unhandled error in asyncAccept."
    var observer: suspend (serverSocket: AsyncServerSocket<T>, socket: T?, exception: Throwable?) -> Unit = { serverSocket, socket, throwable ->
        if (throwable != null) {
            println(unhandled)
            serverSocket.post()
            throw AssertionError(unhandled)
        }
    }
    suspend fun observe(block: suspend (serverSocket: AsyncServerSocket<T>, socket: T?, exception: Throwable?) -> Unit) {
        await()
        observer = block
    }
    suspend fun closeOnError() {
        observe { serverSocket, socket, throwable ->
            if (socket != null)
                socket.close()
            else
                serverSocket.close()
        }
    }
    suspend fun invokeObserver(socket: T?, exception: Throwable?) {
        try {
            observer(serverSocket, socket, exception)
        }
        catch (throwable: Throwable) {
            serverSocket.post()
            throw AssertionError(unhandled)
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
            ret.invokeObserver(null, throwable)
            return@startSafeCoroutine
        }
        ret.invokeObserver(null, null)
    }

    return ret
}
