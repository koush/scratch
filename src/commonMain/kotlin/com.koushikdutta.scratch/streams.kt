package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.buffers.WritableBuffers


/**
 * Read into a buffer.
 * Returns true if more data can be read.
 * Returns false if nothing was read, and no further data can be read.
 * Writing data into the buffer, and returning false to indicate end of stream is an
 * invalid implementation:
 * This makes read looping clunky, as false while returning data results in truthy behavior.
 */
typealias AsyncRead = suspend (buffer: WritableBuffers) -> Boolean

/**
 * Write a buffer.
 */
typealias AsyncWrite = suspend (buffer: ReadableBuffers) -> Unit

/**
 * A pipe: Given a read, return a read.
 */
typealias AsyncPipe = (read: AsyncRead) -> AsyncRead

interface AsyncAffinity {
    suspend fun await()
}

/**
 * AsyncSocket provides an AsyncRead and AsyncWrite.
 */
interface AsyncSocket : AsyncAffinity {
    /**
     * If this socket has a i/o thread affinity, this will resume the coroutine onto it.
     * Can be used to perform "simultaneous" reads/writes, without managing serialization:
     * val handler = AsyncHandler(socket::await)
     * // later...
     * handler.post/run {
     *   socket.write(buffer)
     * }
     */
    suspend fun read(buffer: WritableBuffers): Boolean
    suspend fun write(buffer: ReadableBuffers)
    suspend fun close()
}


interface AsyncWrappingSocket : AsyncSocket {
    val socket: AsyncSocket
}

/**
 * Stream this data through a pipe.
 * Returns the AsyncRead that outputs the filtered data.
 */
fun AsyncRead.pipe(pipe: AsyncPipe): AsyncRead {
    return pipe(this)
}

fun ReadableBuffers.reader(): AsyncRead {
    return read@{
        if (isEmpty)
            return@read false
        this.read(it)
        true
    }
}

suspend fun AsyncWrite.drain(buffer: ReadableBuffers) {
    while (buffer.hasRemaining()) {
        this(buffer)
    }
}

suspend fun AsyncRead.drain() {
    val temp = ByteBufferList()
    while (this(temp)) {
        temp.free()
    }
}

suspend fun AsyncRead.copy(asyncWrite: AsyncWrite) {
    val bytes = ByteBufferList()
    while (this(bytes)) {
        asyncWrite.drain(bytes)
    }
}

fun AsyncWrite.writePipe(pipe: AsyncPipe): AsyncWrite {
    val yielder = Cooperator()
    var pending: ReadableBuffers? = null
    var closed = false

    val read: AsyncRead = {
        // wait for a write
        yielder.yield()
        val ret = pending!!.read(it)
        // finish the write
        yielder.resume()
        ret || !closed
    }

    val output = pipe(read)

    val result = async {
        try {
            val buffer = ByteBufferList()
            while (output(buffer)) {
                this(buffer)
            }
        }
        finally {
            closed = true
        }
    }

    val checkWriter = {
        result.rethrow()
        if (closed)
            throw Exception("socket has been closed")
    }

    return {
        checkWriter()

        pending = it
        // notify a read, and wait for the continuation
        yielder.yield()
        pending = null

        checkWriter()
    }
}

operator fun AsyncRead.plus(other: AsyncRead): AsyncRead {
    return {
        this(it) || other(it)
    }
}
