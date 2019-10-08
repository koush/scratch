package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.buffers.WritableBuffers
import java.io.IOException

fun createAsyncSocketPipe(): AsyncSocket {
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


fun createSocketPair() : Pair<AsyncSocket, AsyncSocket> {
    val p1 = createAsyncSocketPipe()
    val p2 = createAsyncSocketPipe()

    val close: suspend () -> Unit = {
        p1.close()
        p2.close()
    }

    return Pair(CompositeSocket(p1::read, p2::write, close), CompositeSocket(p2::read, p1::write, close))
}