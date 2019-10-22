package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.buffers.WritableBuffers
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext


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

/**
 * An object with thread affinity.
 */
interface AsyncAffinity {
    /**
     * Ensure that coroutine execution is on the owner thread.
     */
    suspend fun await()

    /**
     * Post (suspend and resume) execution onto the next loop of the owner thread queue.
     * This clears the coroutine stack trace.
     */
    suspend fun post()
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

operator fun AsyncRead.plus(other: AsyncRead): AsyncRead {
    return {
        this(it) || other(it)
    }
}
