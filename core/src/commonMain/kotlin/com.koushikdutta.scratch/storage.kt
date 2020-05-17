package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.buffers.WritableBuffers

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
