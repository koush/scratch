package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.buffers.WritableBuffers

internal class PipeSocket: AsyncSocket {
    private val baton = Baton<ReadableBuffers?>()
    override suspend fun await() {
    }

    override suspend fun post() {
    }

    override suspend fun read(buffer: WritableBuffers): Boolean {
        return baton.pass(null) {
            it.rethrow()
            if (!it.finished && it.value == null && !it.resumed)
                throw IOException("read cancelled by another read")

            // handle the buffer transfer inside the baton lock
            it.value?.read(buffer)
            !it.finished
        }
    }

    override suspend fun write(buffer: ReadableBuffers) {
        if (buffer.isEmpty)
            return
        baton.pass(buffer) {
            it.rethrow()
            if (!it.finished && it.value != null && it.resumed)
                throw IOException("write already in progress")
        }
    }

    suspend fun close(throwable: Throwable) {
        // ignore errors
        baton.raiseFinish(throwable) { true }
    }

    override suspend fun close() {
        // ignore errors
        baton.finish(null) { true }
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
    val sockets = AsyncQueue<AsyncSocket>()
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