package com.koushikdutta.scratch

import com.koushikdutta.scratch.async.async
import com.koushikdutta.scratch.buffers.ByteBuffer
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
 * Write from a buffer.
 * This function may return without fully writing the entire buffer.
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

    companion object {
        val NO_AFFINITY = object : AsyncAffinity {
            override suspend fun await() {
            }
        }
    }
}

interface AsyncResource {
    suspend fun close()
}

interface AsyncInput : AsyncResource {
    /**
     * Read into a buffer.
     * Returns true if more data can be read.
     * Returns false if nothing was read, and no further data can be read.
     * Writing data into the buffer, and returning false to indicate end of stream is an
     * invalid implementation:
     * This makes read looping clunky, as false while returning data results in truthy behavior.
     */
    suspend fun read(buffer: WritableBuffers): Boolean
}

interface AsyncOutput : AsyncResource {
    /**
     * Write from a buffer.
     * This function may return without fully writing the entire buffer.
     */
    suspend fun write(buffer: ReadableBuffers)
}

/**
 * AsyncSocket provides an AsyncRead and AsyncWrite.
 * Must be closed to free the underlying resources.
 */
interface AsyncSocket : AsyncInput, AsyncOutput, AsyncAffinity

/**
 * AsyncSocket that filters another AsyncSocket.
 * For example, bidirectional chunk encoding or SSL encryption.
 */
interface AsyncWrappingSocket : AsyncSocket {
    val socket: AsyncSocket
}

class AsyncDoubleReadException: IOException("read cancelled by another read")
class AsyncDoubleWriteException: IOException("write already in progress")
class AsyncWriteClosedException: IOException()

internal fun <T> genericPipe(read: T, pipe: GenericAsyncPipe<T>): AsyncRead {
    val buffer = ByteBufferList()
    val iterator = asyncIterator<Unit> {
        val scope = AsyncPipeIteratorScope(buffer, this)
        pipe(scope, read)
    }

    return read@{
        buffer.takeReclaimedBuffers(it)
        while (true) {
            try {
                if (!iterator.hasNext())
                    return@read false
                iterator.next()
                buffer.read(it)
                return@read true
            }
            catch (iteratorException: AsyncIteratorConcurrentException) {
                if (iteratorException.resumed)
                    continue
                throw AsyncDoubleReadException()
            }
        }
        // this is dead code but the IDE is complaining.
        true
    }
}

/**
 * Stream this data through a pipe.
 * Returns the AsyncRead that outputs the filtered data.
 */
fun AsyncRead.pipe(pipe: AsyncPipe): AsyncRead {
    return genericPipe(this, pipe)
}

suspend fun AsyncSocket.stream(peer: AsyncSocket) {
    val other = async {
        peer::read.copy(::write)
    }
    ::read.copy(peer::write)
    other.await()
}

fun AsyncRead.tee(asyncWrite: AsyncWrite, callback: suspend (throwable: Throwable?) -> Unit = { if (it != null) throw it }): AsyncRead {
    val self = this
    return pipe {
        val tmp = ByteBufferList()
        var error = false
        while (self(buffer)) {
            if (!error) {
                val dup = buffer.readByteBuffer()
                tmp.add(ByteBufferList.deepCopy(dup))
                try {
                    asyncWrite.drain(tmp)
                }
                catch (throwable: Throwable) {
                    error = true
                    callback(throwable)
                }
                tmp.free()

                buffer.add(dup)
            }

            flush()
        }

        callback(null)
    }
}

fun ReadableBuffers.createReader(): AsyncRead {
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

suspend fun AsyncRead.drain(buffer: WritableBuffers) {
    while (this(buffer)) {
        // prevent ide complaining about empty body
    }
}

suspend fun AsyncRead.copy(asyncWrite: AsyncWrite, buffer: ByteBufferList = ByteBufferList()) {
    while (this(buffer)) {
        asyncWrite.drain(buffer)
    }
}

operator fun AsyncRead.plus(other: AsyncRead): AsyncRead {
    return {
        this(it) || other(it)
    }
}


fun AsyncIterator<AsyncRead>.join(): AsyncRead {
    var read: AsyncRead? = null
    return read@{
        if (read == null) {
            if (!hasNext())
                return@read false
            read = next()
        }

        if (!read!!(it))
            read = null

        true
    }
}

fun AsyncIterator<ByteBuffer>.createAsyncReadFromByteBuffers(): AsyncRead {
    return read@{
        if (!hasNext())
            return@read false
        it.add(next())
        true
    }
}

fun AsyncIterator<ByteBufferList>.createAsyncReadFromByteBufferLists(): AsyncRead {
    return read@{
        if (!hasNext())
            return@read false
        it.add(next())
        true
    }
}
