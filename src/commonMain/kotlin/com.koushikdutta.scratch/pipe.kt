package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.buffers.WritableBuffers

class PipeSocket: AsyncSocket {
    val yielder = Cooperator()
    var pending: ReadableBuffers? = null
    var closed = false

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

        val ret = pending!!.read(buffer)
        pending = null
        yielder.resume()
        return ret || !closed
    }

    override suspend fun write(buffer: ReadableBuffers) {
        if (closed)
            throw Exception("pipe closed")
        if (buffer.isEmpty)
            return
        pending = buffer
        yielder.yield()
        pending = null
        if (closed)
            throw Exception("pipe closed")
    }

    override suspend fun close() {
        closed = true
        yielder.resume()
    }
}

fun createAsyncPipeSocket(): AsyncSocket {
    return PipeSocket()
}

fun createAsyncPipeSocketPair() : Pair<AsyncSocket, AsyncSocket> {
    val p1 = createAsyncPipeSocket()
    val p2 = createAsyncPipeSocket()

    val close: suspend () -> Unit = {
        p1.close()
        p2.close()
    }

    return Pair(CompositeSocket({p1.read(it)}, {p2.write(it)}, close), CompositeSocket({p2.read(it)}, {p1.write(it)}, close))
}

private class CompositeSocket(val input: AsyncRead, val output: AsyncWrite, val outputClose: suspend() -> Unit) : AsyncSocket {
    override suspend fun await() {
    }

    override suspend fun read(buffer: WritableBuffers): Boolean = input(buffer)

    override suspend fun write(buffer: ReadableBuffers) = output(buffer)

    override suspend fun close() = outputClose()
}

class AsyncPipeServerSocket : AsyncServerSocket {
    val sockets = AsyncDequeueIterator<AsyncSocket>()
    var closed = false

    suspend fun connect(): AsyncSocket {
        check(!closed) { "server closed" }

        val pair = createAsyncPipeSocketPair()

        sockets.add(pair.first)
        return pair.second
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

fun createAsyncPipeServerSocket(): AsyncPipeServerSocket {
    return AsyncPipeServerSocket()
}