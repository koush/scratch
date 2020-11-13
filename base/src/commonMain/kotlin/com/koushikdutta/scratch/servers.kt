package com.koushikdutta.scratch

import com.koushikdutta.scratch.async.async
import com.koushikdutta.scratch.async.startSafeCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async

/**
 * AsyncServerSocket accepts incoming AsyncSocket clients.
 */
interface AsyncServerSocket<T: AsyncSocket> : AsyncAffinity {
    fun accept(): AsyncIterable<out T>
    suspend fun close()
    suspend fun close(throwable: Throwable)
}

interface AsyncServer {
    fun <T: AsyncSocket> listen(server: AsyncServerSocket<T>): AsyncAcceptObserver<T>
}

class AsyncAcceptObserver<T: AsyncSocket> internal constructor(private val serverSocket: AsyncServerSocket<T>, block: suspend T.() ->Unit) {
    private var observer: suspend AsyncAcceptObserver<T>.(socket: T, exception: Throwable?) -> Unit = { socket, throwable ->
        socket.close()
        if (throwable != null)
            serverSocket.close(throwable)
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

    private suspend fun invokeObserver(socket: T, exception: Throwable?) {
        try {
            observer(socket, exception)
        }
        catch (throwable: Throwable) {
            serverSocket.await()
            throw throwable
        }
    }

    private val closePromise = serverSocket.async {
        for (socket in serverSocket.accept()) {
            startSafeCoroutine socketCoroutine@{
                try {
                    block(socket)
                }
                catch (throwable: Throwable) {
                    invokeObserver(socket, throwable)
                    return@socketCoroutine
                }
                invokeObserver(socket, null)
            }
        }
    }
    suspend fun awaitClose() = closePromise.await()
}

fun <T: AsyncSocket> AsyncServerSocket<T>.acceptAsync(block: suspend T.() ->Unit) = AsyncAcceptObserver(this, block)
