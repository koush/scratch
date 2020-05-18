package com.koushikdutta.scratch

import com.koushikdutta.scratch.AsyncAffinity.Companion.NO_AFFINITY
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.buffers.WritableBuffers

private val interruptBuffer = ByteBufferList()

class PipeSocket: AsyncSocket, AsyncAffinity by NO_AFFINITY {
    private val baton = Baton<ReadableBuffers?>()

    override suspend fun read(buffer: WritableBuffers): Boolean {
        return baton.pass(null) {
            it.getOrThrow()
            if (it.getOrThrow() !== interruptBuffer) {
                if (!it.finished && it.getOrThrow() == null && !it.resumed)
                    throw AsyncDoubleReadException()

                // handle the buffer transfer inside the baton lock
                it.getOrThrow()?.read(buffer)
                !it.finished
            }
            else {
                true
            }
        }
    }

    override suspend fun write(buffer: ReadableBuffers) {
        if (buffer.isEmpty)
            return
        baton.pass(buffer) {
            it.getOrThrow()
            if (!it.finished && it.getOrThrow() != null && it.resumed)
                throw AsyncDoubleWriteException()
            if (it.finished)
                throw AsyncWriteClosedException()
        }
    }

    private val interruptRead: BatonTakeCondition<ReadableBuffers?> = {
        it.getOrThrow() == null
    }

    fun interruptRead() {
        baton.takeIf(interruptBuffer, interruptRead)
    }

    private val interruptWrite: BatonTakeCondition<ReadableBuffers?> = {
        it.getOrThrow() != null
    }

    fun interruptWrite() {
        baton.takeIf(null, interruptWrite)
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

class PairedPipeSocket(val input: AsyncRead, val output: AsyncWrite, val outputClose: suspend() -> Unit) : AsyncSocket, AsyncAffinity by NO_AFFINITY {
    override suspend fun read(buffer: WritableBuffers): Boolean = input(buffer)

    override suspend fun write(buffer: ReadableBuffers) = output(buffer)

    override suspend fun close() = outputClose()
}

class AsyncPipeServerSocket : AsyncServerSocket<AsyncSocket>, AsyncAffinity by NO_AFFINITY {
    val sockets = AsyncQueue<AsyncSocket>()

    fun connect(): AsyncSocket {
        val pair = createAsyncPipeSocketPair()
        sockets.add(pair.first)
        return pair.second
    }

    override fun accept(): AsyncIterable<out AsyncSocket> {
        return sockets
    }

    override suspend fun close() {
        sockets.end()
    }

    override suspend fun close(throwable: Throwable) {
        sockets.end(throwable)
    }
}

fun createAsyncPipeServerSocket(): AsyncPipeServerSocket {
    return AsyncPipeServerSocket()
}