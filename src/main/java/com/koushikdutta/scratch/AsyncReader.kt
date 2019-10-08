package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.WritableBuffers
import java.nio.charset.Charset
import kotlin.math.min

/**
 * Create an AsyncReader that provides advanced reading operations
 * on an AsyncRead stream.
 */
class AsyncReader(val input: AsyncRead) {
    private val pending = ByteBufferList()
    val buffered: Int
        get() = pending.remaining()

    /**
     * Read the data into the buffer.
     * The size of the buffer can be read from the buffered property.
     * Follows the same return convention as AsyncRead:
     * Returns true if more data can be read.
     * Returns false if nothing was read, and no further data can be read.
     */
    suspend fun readBuffer(): Boolean {
        val ret = input(pending)
        return ret || pending.hasRemaining()
    }

    /**
     * Read the underlying input.
     */
    suspend fun read(buffer: WritableBuffers): Boolean {
        if (pending.hasRemaining()) {
            pending.get(buffer)
            return true
        }
        return input(buffer)
    }

    /**
     * Read any buffered data in the reader.
     * Returns true if there was data buffered in the reader, false otherwise.
     */
    fun readPending(buffer: WritableBuffers): Boolean {
        return pending.get(buffer)
    }

    /**
     * Scans the read for a specified byte sequence.
     * Returns true if found, returns false if the stream ended before
     * the sequence was found.
     */
    suspend fun readScan(buffer: WritableBuffers, scan: ByteArray): Boolean {
        while (!pending.getScan(buffer, scan)) {
            if (!input(pending))
                return false
        }
        return true
    }

    /**
     * Scans the read for a specified byte sequence.
     * Returns true if found, returns false if the stream ended before
     * the sequence was found. Returns null otherwise and the read
     * can continue to be scanned for chunks.
     */
    suspend fun readScanChunk(buffer: WritableBuffers, scan: ByteArray): Boolean? {
        if (!input(pending))
            return pending.getScan(buffer, scan)

        if (pending.getScan(buffer, scan))
            return true
        return null
    }

    /**
     * Scan the read for a specified string sequence.
     * Returns true if found, returns false if the read ended before
     * the sequence was found.
     */
    suspend fun readScanString(buffer: WritableBuffers, scanString: String, charset: Charset = Charsets.UTF_8): Boolean {
        val scan = scanString.toByteArray(charset)
        return readScan(buffer, scan)
    }

    /**
     * Scan the read for a specified string sequence.
     * Returns the string data up to and including the string sequence, or the end of stream.
     */
    suspend fun readScanString(scanString: String, charset: Charset = Charsets.UTF_8): String {
        val buffer = ByteBufferList()
        readScanString(buffer, scanString, charset)
        return buffer.string
    }

    /**
     * Perform a read for a given length of bytes.
     * Returns true if the read completed fully, returns false if the end of stream
     * was reached.
     */
    suspend fun readLength(buffer: WritableBuffers, length: Int): Boolean {
        // keep reading until we can fulfill the entire read
        while (pending.remaining() < length) {
            if (!input(pending)) {
                // end of stream reached, just bail with whatever is left in the buffer.
                pending.get(buffer)
                return false
            }
        }

        pending.get(buffer, length)
        return true
    }

    /**
     * Perform a read for a given length of bytes and return the result as a string.
     * Returns a string up to the given length, or shorter if end of stream was reached.
     */
    suspend fun readString(length: Int, charset: Charset = Charsets.UTF_8): String {
        return String(readBytes(length), charset)
    }

    /**
     * Perform a read for up to the given length of bytes.
     * Follows the same return convention as AsyncRead:
     * Returns true if more data can be read.
     * Returns false if nothing was read, and no further data can be read.
     */
    suspend fun readChunk(buffer: WritableBuffers, length: Int): Boolean {
        if (pending.isEmpty) {
            if (!input(pending))
                return false
        }

        val toRead = min(length, pending.remaining())
        pending.get(buffer, toRead)
        return true
    }

    /**
     * Read a number of bytes.
     */
    suspend fun readBytes(length: Int): ByteArray {
        while (pending.remaining() < length) {
            if (!input(pending))
                break
        }
        return pending.getBytes(min(pending.remaining(), length))
    }

    /**
     * Peek a number of bytes.
     */
    suspend fun peekBytes(length: Int): ByteArray {
        while (pending.remaining() < length) {
            if (!input(pending))
                break
        }
        return pending.peekBytes(min(pending.remaining(), length))
    }

    /**
     * Peek a number of bytes as a String.
     */
    suspend fun peekString(length: Int, charset: Charset = Charsets.UTF_8): String {
        return String(peekBytes(length), charset)
    }

    /**
     * Skip a number of bytes.
     */
    suspend fun skip(length: Int): Boolean {
        var length = length

        while (length > 0) {
            if (pending.isEmpty && !input(pending))
                return false

            val skip = min(length, pending.remaining())
            pending.skip(skip)
            length -= skip
        }

        return true
    }

    /**
     * Stream this data through a pipe.
     * Returns the AsyncRead that outputs the filtered data.
     */
    fun pipe(pipe: AsyncReaderPipe): AsyncRead {
        return pipe(this)
    }
}

typealias AsyncReaderPipe = (reader: AsyncReader) -> AsyncRead