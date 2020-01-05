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
    suspend fun setPosition(position: Long)
    /**
     * Reads up to length, but not necessarily in entirety, length bytes.
     */
    suspend fun readPosition(position: Long, length: Long, buffer: WritableBuffers): Boolean
}

/**
 * Create an AsyncRead that only reads a specific position and length
 * of an AsyncRandomAccessInput.
 */
fun AsyncRandomAccessInput.slice(position: Long, length: Long): AsyncRead {
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

/**
 * AsyncRandomAccessStorage provides random access read and write to
 * resources. The resource may be locally stored, like a file, or remotely
 * modified, like a network file or http resource.
 */
interface AsyncRandomAccessStorage : AsyncOutput, AsyncRandomAccessInput {
    suspend fun writePosition(position: Long, buffer: ReadableBuffers) {
        setPosition(position)
        write(buffer)
    }
    suspend fun truncate(size: Long)
}
