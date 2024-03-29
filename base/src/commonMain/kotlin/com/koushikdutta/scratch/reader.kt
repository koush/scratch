package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.ByteOrder
import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.buffers.WritableBuffers
import kotlin.math.min

class ReadScanException(val finalData: ReadableBuffers): IOException("Stream ended before new line was received.")

/**
 * Create an AsyncReader that provides advanced reading operations
 * on an AsyncRead stream.
 */
class AsyncReader(val input: AsyncRead): AsyncRead {
    constructor(block: suspend(buffer: WritableBuffers) -> Boolean): this(AsyncRead(block))
    private val pending = ByteBufferList()
    val buffered: Int
        get() = pending.remaining()

    var order: ByteOrder = ByteOrder.BIG_ENDIAN

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
    override suspend fun read(buffer: WritableBuffers): Boolean {
        if (pending.hasRemaining()) {
            pending.read(buffer)
            return true
        }
        return input(buffer)
    }

    /**
     * Read any buffered data in the reader.
     * Returns true if there was data buffered in the reader, false otherwise.
     */
    fun readPending(buffer: WritableBuffers): Boolean {
        return pending.read(buffer)
    }

    /**
     * Scans the read for a specified byte sequence.
     * Returns true if found, returns false if the stream ended before
     * the sequence was found.
     */
    suspend fun readScan(buffer: WritableBuffers, scan: ByteArray): Boolean {
        while (!pending.readScan(buffer, scan)) {
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
            return pending.readScan(buffer, scan)

        if (pending.readScan(buffer, scan))
            return true
        return null
    }

    /**
     * Scan the read for a specified string sequence.
     * Returns true if found, returns false if the read ended before
     * the sequence was found.
     */
    suspend fun readScanUtf8String(buffer: WritableBuffers, scanString: String): Boolean {
        val scan = scanString.encodeToByteArray()
        return readScan(buffer, scan)
    }

    /**
     * Scan the read for a specified string sequence.
     * Returns the string data up to and including the string sequence.
     * Throws [ReadScanException] if end of stream is reached before the scan string is found.
     */
    suspend fun readScanUtf8String(scanString: String): String {
        val buffer = ByteBufferList()
        if (!readScanUtf8String(buffer, scanString))
            throw ReadScanException(buffer)
        val scanned = buffer.readUtf8String()
        pending.takeReclaimedBuffers(buffer)
        return scanned
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
                pending.read(buffer)
                return false
            }
        }

        pending.read(buffer, length)
        return true
    }

    /**
     * Perform a read for a given length of bytes and return the result as a string.
     * Returns a string up to the given length, or shorter if end of stream was reached.
     */
    suspend fun readUtf8String(length: Int): String {
        return readBytes(length).decodeToString()
    }

    /**
     * Perform a read for up to the given length of bytes.
     * Follows the same return convention as AsyncRead:
     * Returns true if more data can be read.
     * Returns false if nothing was read, and no further data can be read.
     */
    suspend fun readChunk(buffer: WritableBuffers, length: Int): Boolean {
        pending.takeReclaimedBuffers(buffer)
        if (pending.isEmpty) {
            if (!input(pending))
                return false
        }

        val toRead = min(length, pending.remaining())
        pending.read(buffer, toRead)
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
        return pending.readBytes(min(pending.remaining(), length))
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
    suspend fun peekString(length: Int): String {
        return peekBytes(length).decodeToString()
    }

    /**
     * Skip a number of bytes.
     */
    suspend fun skip(length: Int): Boolean {
        var tmp = length

        while (tmp > 0) {
            if (pending.isEmpty && !input(pending))
                return false

            val skip = min(tmp, pending.remaining())
            pending.skip(skip)
            tmp -= skip
        }

        return true
    }

    /**
     * Stream this data through a pipe.
     * Returns the AsyncRead that outputs the filtered data.
     */
    fun pipe(pipe: AsyncReaderPipe): AsyncRead {
        return genericPipe(this, pipe)
    }

    private suspend fun ensureBuffered(length: Int) {
        while (buffered < length && readBuffer()) {
        }
        if (buffered < length)
            throw IOException("AsyncReader unable to read $length bytes")
    }

    suspend fun readByte(): Byte {
        ensureBuffered(1)
        return pending.readByte()
    }

    suspend fun readShort(order: ByteOrder = this.order): Short {
        ensureBuffered(2)
        pending.order(order)
        return pending.readShort()
    }

    suspend fun readInt(order: ByteOrder = this.order): Int {
        ensureBuffered(4)
        pending.order(order)
        return pending.readInt()
    }

    suspend fun readLong(order: ByteOrder = this.order): Long {
        ensureBuffered(8)
        pending.order(order)
        return pending.readLong()
    }
}

/**
 * Scan the read for a line string ending with newline character "\n".
 * Returns the string, without the newline character.
 * Throws [ReadScanException] if end of stream is reached before the newline is found.
 */
suspend fun AsyncReader.readLine(): String {
    return readScanUtf8String("\n").trimEnd('\n')
}

typealias AsyncReaderPipe = suspend AsyncPipeIteratorScope.(reader: AsyncReader) -> Unit
