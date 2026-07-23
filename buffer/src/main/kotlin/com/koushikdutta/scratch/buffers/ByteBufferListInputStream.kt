package com.koushikdutta.scratch.buffers

import java.io.IOException
import java.io.InputStream

/**
 * Created by koush on 6/1/13.
 */
class ByteBufferListInputStream(var bb: ByteBufferList) : InputStream() {
    @Throws(IOException::class)
    override fun read(): Int {
        return if (bb.remaining() <= 0) -1 else bb.readByte().toInt() and 0x000000ff
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray): Int {
        return this.read(buffer, 0, buffer.size)
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bb.remaining() <= 0) return -1
        val toRead = Math.min(length, bb.remaining())
        bb.read(buffer, offset, toRead)
        return toRead
    }
}