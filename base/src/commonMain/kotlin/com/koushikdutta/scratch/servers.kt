package com.koushikdutta.scratch

import com.koushikdutta.scratch.async.async
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
    suspend fun <T: AsyncSocket> listen(server: AsyncServerSocket<T>)
}
fun <T: AsyncSocket> AsyncServer.listenAsync(server: AsyncServerSocket<T>) = server.async {
    listen(server)
}

suspend fun <T: AsyncSocket> AsyncServerSocket<T>.accept(ignoreErrors: Boolean = false, leaveOpen: Boolean = false, block: suspend T.() -> Unit) {
    for (socket in accept()) {
        startSafeCoroutine socketCoroutine@{
            try {
                block(socket)
            }
            catch (throwable: Throwable) {
                if (!ignoreErrors)
                    close(throwable)
            }
            finally {
                if (!leaveOpen)
                    socket.close()
            }
        }
    }
}

fun <T: AsyncSocket> AsyncServerSocket<T>.acceptAsync(ignoreErrors: Boolean = false, leaveOpen: Boolean = false, block: suspend T.() -> Unit) = async {
    accept(ignoreErrors, leaveOpen, block)
}
