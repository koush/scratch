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
class AsyncPipeIteratorScope internal constructor(val buffer: ByteBufferList, private val scope: AsyncIteratorScope<Unit>) {
    suspend fun flush() {
        scope.yield(Unit)
    }
}

typealias AsyncPipe = suspend AsyncPipeIteratorScope.(read: AsyncRead) -> Unit
internal typealias GenericAsyncPipe<T> = suspend AsyncPipeIteratorScope.(read: T) -> Unit

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

    companion object {
        val NO_AFFINITY = object : AsyncAffinity {
            override suspend fun post() {
            }
            override suspend fun await() {
            }
        }
    }
}

interface AsyncResource : AsyncAffinity {
    suspend fun close()
}

interface AsyncInput : AsyncResource {
    suspend fun read(buffer: WritableBuffers): Boolean
}

interface AsyncOutput : AsyncResource {
    suspend fun write(buffer: ReadableBuffers)
}

/**
 * AsyncSocket provides an AsyncRead and AsyncWrite.
 * Must be closed to free the underlying resources.
 */
interface AsyncSocket : AsyncInput, AsyncOutput

/**
 * AsyncSocket that filters another AsyncSocket.
 * For example, bidirectional chunk encoding or SSL encryption.
 */
interface AsyncWrappingSocket : AsyncSocket {
    val socket: AsyncSocket
}

internal fun <T> genericPipe(read: T, pipe: GenericAsyncPipe<T>): AsyncRead {
    val buffer = ByteBufferList()
    val iterator = asyncIterator<Unit> {
        val scope = AsyncPipeIteratorScope(buffer, this)
        pipe(scope, read)
    }

    return read@{
        buffer.takeReclaimedBuffers(it)
        if (!iterator.hasNext())
            return@read false
        iterator.next()
        buffer.read(it)
        return@read true
    }
}

/**
 * Stream this data through a pipe.
 * Returns the AsyncRead that outputs the filtered data.
 */
fun AsyncRead.pipe(pipe: AsyncPipe): AsyncRead {
    return genericPipe(this, pipe)
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
