package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.buffers.WritableBuffers

private data class PipeData(val write: ReadableBuffers? = null, val closed: Boolean = false)

private val pipeReadRequest = PipeData()
private val pipeClosedRequest = PipeData(closed = true)

private class PipeSocket: AsyncSocket {
    private val baton = Baton<PipeData>()
    override suspend fun await() {
    }

    override suspend fun post() {
    }

    override suspend fun read(buffer: WritableBuffers): Boolean {
        // wait for a write
        while (true) {
            val result = baton.passResult(pipeReadRequest) {
                // handle the buffer transfer inside the baton lock
                it.value.write?.read(buffer)
            }

            if (result.value.closed)
                return false

            if (result.value.write == null) {
                // cancel previous read requests on double read calls
                if (!result.resumed)
                    return true
                else
                    continue
            }
            // got a write request, so continue on.
            break
        }
        return true
    }

    override suspend fun write(buffer: ReadableBuffers) {
        val result = baton.pass(PipeData(write = buffer))
        if (result.closed)
            throw Exception("pipe closed")
    }

    override suspend fun close() {
        startSafeCoroutine {
            while (true) {
                val result = baton.passResult(pipeClosedRequest)
                // prevent double close looping by ending the coroutine that
                // resumed
                if (result.value.closed && result.resumed)
                    break
            }
        }
    }
}

fun createAsyncPipeSocket(): AsyncSocket {
    return PipeSocket()
}

fun createAsyncPipeSocketPair() : Pair<PairedPipeSocket, PairedPipeSocket> {
    val p1 = createAsyncPipeSocket()
    val p2 = createAsyncPipeSocket()

    val close: suspend () -> Unit = {
        p1.close()
        p2.close()
    }

    return Pair(PairedPipeSocket({p1.read(it)}, {p2.write(it)}, close), PairedPipeSocket({p2.read(it)}, {p1.write(it)}, close))
}

class PairedPipeSocket(val input: AsyncRead, val output: AsyncWrite, val outputClose: suspend() -> Unit) : AsyncSocket {
    override suspend fun read(buffer: WritableBuffers): Boolean = input(buffer)

    override suspend fun write(buffer: ReadableBuffers) = output(buffer)

    override suspend fun close() = outputClose()

    override suspend fun await() {
    }

    override suspend fun post() {
    }
}

class AsyncPipeServerSocket : AsyncServerSocket<AsyncSocket> {
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

    override suspend fun post() {
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