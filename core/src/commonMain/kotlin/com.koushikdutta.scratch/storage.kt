package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.*
import kotlin.math.min

/**
 * AsyncRandomAccessInput provides random access read access to
 * resources. The resource may be locally stored, like a file, or remotely
 * retrieved, like an http resource.
 */
interface AsyncRandomAccessInput : AsyncInput {
    suspend fun size(): Long
    suspend fun getPosition(): Long
    suspend fun seekPosition(position: Long)
    /**
     * Reads up to length, but not necessarily in entirety, length bytes.
     */
    suspend fun readPosition(position: Long, length: Long, buffer: WritableBuffers): Boolean
}

/**
 * Create an AsyncRead that only reads a specific position and length
 * of an AsyncRandomAccessInput.
 */
suspend fun AsyncRandomAccessInput.seekRead(position: Long, length: Long): AsyncRead {
    if (position + length > size())
        throw IllegalArgumentException("out of range")

    var total = 0L
    val buffer = ByteBufferList()
    return read@{
        if (total >= length)
            return@read false

        buffer.takeReclaimedBuffers(it)
        val ret = readPosition(position + total, length - total, buffer)
        total += buffer.remaining()
        buffer.read(it)
        ret
    }
}

suspend fun AsyncRandomAccessInput.seekInput(position: Long, length: Long): AsyncInput {
    val read = seekRead(position, length)
    return object : AsyncInput, AsyncResource by this {
        override suspend fun read(buffer: WritableBuffers) = read(buffer)
    }
}

fun AsyncRandomAccessInput.slice(position: Long, length: Long): AsyncRead {
    var read = 0L
    return {
        val start = it.remaining()
        val eos = readPosition(position + read, length - read, it)
        read += it.remaining() - start
        if (read == length)
            false
        else
            eos
    }
}

/**
 * AsyncRandomAccessStorage provides random access read and write to
 * resources. The resource may be locally stored, like a file, or remotely
 * modified, like a network file or http resource.
 */
interface AsyncRandomAccessStorage : AsyncOutput, AsyncRandomAccessInput {
    suspend fun writePosition(position: Long, buffer: ReadableBuffers) {
        seekPosition(position)
        this::write.drain(buffer)
    }
    suspend fun truncate(size: Long)
}


interface AsyncSliceable {
    suspend fun size(): Long
    suspend fun slice(position: Long, length: Long): AsyncInput
}

class BufferStorage(buffer: ByteBufferList) : AsyncRandomAccessStorage, AsyncAffinity by AsyncAffinity.NO_AFFINITY {
    private var byteBuffer = allocateByteBuffer(buffer.remaining())

    init {
        byteBuffer.put(buffer.readByteBuffer())
        byteBuffer.flip()
    }

    fun deepCopyByteBuffer(): ByteBuffer {
        val buffer = byteBuffer.duplicate()
        buffer.clear()
        return ByteBufferList.deepCopyExactSize(buffer)
    }

    override suspend fun size(): Long {
        return byteBuffer.capacity().toLong()
    }

    override suspend fun getPosition(): Long {
        return byteBuffer.position().toLong()
    }

    override suspend fun seekPosition(position: Long) {
        byteBuffer.position(position.toInt())
    }

    override suspend fun readPosition(position: Long, length: Long, buffer: WritableBuffers): Boolean {
        byteBuffer.position(position.toInt())
        byteBuffer.limit(byteBuffer.position() + length.toInt())
        if (!byteBuffer.hasRemaining())
            return false

        buffer.putAllocatedBuffer(length.toInt()) {
            it.put(byteBuffer)
        }

        return true
    }

    override suspend fun read(buffer: WritableBuffers): Boolean {
        byteBuffer.limit(byteBuffer.capacity())
        if (!byteBuffer.hasRemaining())
            return false
        buffer.putAllocatedBuffer(byteBuffer.remaining()) {
            it.put(byteBuffer)
        }
        return true
    }

    var closed = false
    override suspend fun close() {
        closed = true
    }

    override suspend fun truncate(size: Long) {
        val position = byteBuffer.position()
        byteBuffer.limit(min(byteBuffer.capacity(), size.toInt()))
        byteBuffer.position(0)
        val newBuffer = ByteBufferList.deepCopyExactSize(byteBuffer)
        newBuffer.position(min(position, newBuffer.capacity()))
        byteBuffer = newBuffer
    }

    override suspend fun write(buffer: ReadableBuffers) {
        // write into existing allocation if possible
        if (byteBuffer.capacity() - byteBuffer.position() >= buffer.remaining()) {
            byteBuffer.limit(byteBuffer.capacity())
            byteBuffer.put(buffer.readByteBuffer())
            return
        }

        val dest = ByteBufferList()
        val src = ByteBufferList()
        val position = byteBuffer.position()
        byteBuffer.clear()
        src.add(byteBuffer)
        src.read(dest, position)
        val read = buffer.remaining()
        buffer.read(dest)
        src.skip(min(src.remaining(), read))
        src.read(dest)

        byteBuffer = ByteBufferList.deepCopyExactSize(dest.readByteBuffer())
        byteBuffer.position(byteBuffer.capacity())
    }
}
