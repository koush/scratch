package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.WritableBuffers
import java.nio.charset.Charset
import kotlin.math.min

class AsyncReader(val input: AsyncRead) {
    private val pending = ByteBufferList()

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
        println("ok")
        return pending.get(buffer)
    }

    /**
     * Scans the read for a specified byte sequence.
     * Returns true if found, returns false if the read ended before
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

    fun pipe(pipe: AsyncReaderPipe): AsyncRead {
        return pipe(this)
    }
}

typealias AsyncReaderPipe = (reader: AsyncReader) -> AsyncRead