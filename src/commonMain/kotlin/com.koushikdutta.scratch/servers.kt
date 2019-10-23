package com.koushikdutta.scratch

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/**
 * AsyncServerSocket accepts incoming AsyncSocket clients.
 */
interface AsyncServerSocket<T: AsyncSocket> : AsyncAffinity {
    fun accept(): AsyncIterable<out T>
    suspend fun close()
}

class AsyncAcceptObserver<T: AsyncSocket> internal constructor(internal val serverSocket: AsyncServerSocket<T>): AsyncAffinity by serverSocket {
    private final val unhandled = "unhandled error in asyncAccept."
    var observer: suspend (serverSocket: AsyncServerSocket<T>, socket: T?, exception: Throwable?) -> Unit = { serverSocket, socket, throwable ->
        if (throwable != null) {
            println(unhandled)
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
                    catch (throwable: Exception) {
                        ret.observer(this, socket, throwable)
                        return@socketCoroutine
                    }
                    ret.observer(this, socket, null)
                }
            }
        }
        catch (throwable: Exception) {
            ret.observer(this, null, throwable)
            return@startSafeCoroutine
        }
        ret.observer(this, null, null)
    }

    return ret
}
