package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.buffers.WritableBuffers
import java.io.IOException

fun createAsyncPipeSocket(): AsyncSocket {
    val yielder = Cooperator()
    var pending: ReadableBuffers? = null
    var closed = false

    return object : AsyncSocket {
        override suspend fun await() {
        }

        override suspend fun read(buffer: WritableBuffers): Boolean {
            if (closed)
                return false
            if (pending == null) {
                yielder.yield()
                if (closed)
                    return false
            }

            val ret = pending!!.get(buffer)
            pending = null
            yielder.resume()
            return ret || !closed
        }

        override suspend fun write(buffer: ReadableBuffers) {
            if (closed)
                throw IOException("pipe closed")
            if (buffer.isEmpty)
                return
            pending = buffer
            yielder.yield()
            pending = null
            if (closed)
                throw IOException("pipe closed")
        }

        override suspend fun close() {
            closed = true
            yielder.resume()
        }
    }
}

private class CompositeSocket(val input: AsyncRead, val output: AsyncWrite, val outputClose: suspend() -> Unit) : AsyncSocket {
    override suspend fun await() {
    }

    override suspend fun read(buffer: WritableBuffers): Boolean = input(buffer)

    override suspend fun write(buffer: ReadableBuffers) = output(buffer)

    override suspend fun close() = outputClose()
}


fun createAsyncPipeSocketPair() : Pair<AsyncSocket, AsyncSocket> {
    val p1 = createAsyncPipeSocket()
    val p2 = createAsyncPipeSocket()

    val close: suspend () -> Unit = {
        p1.close()
        p2.close()
    }

    return Pair(CompositeSocket(p1::read, p2::write, close), CompositeSocket(p2::read, p1::write, close))
}

interface AsyncPipeServerSocket : AsyncServerSocket {
    suspend fun connect(): AsyncSocket
}

fun createAsyncPipeServerSocket(): AsyncPipeServerSocket {
    val sockets = AsyncDequeueIterator<AsyncSocket>()
    var closed = false

    return object : AsyncPipeServerSocket {
        override suspend fun connect(): AsyncSocket {
            check(!closed) { "server closed" }
            val ret = createAsyncPipeSocketPair()
            sockets.add(ret.second)
            return ret.first
        }

        override suspend fun await() {
        }

        override fun accept(): AsyncIterable<out AsyncSocket> {
            return sockets
        }

        override suspend fun close() {
            closed = true
        }
    }
}