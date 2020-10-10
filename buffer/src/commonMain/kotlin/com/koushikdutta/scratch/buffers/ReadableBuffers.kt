package com.koushikdutta.scratch.buffers

interface ReadableBuffers : AllocatingBuffers {
    val isEmpty: Boolean
        get() = remaining() == 0

    fun order(): ByteOrder
    fun order(order: ByteOrder)
    fun hasRemaining(): Boolean {
        return !isEmpty
    }

    fun remaining(): Int

    /**
     * Skip the given number of bytes.
     * @param length
     * @return
     */
    fun skip(length: Int): ReadableBuffers

    /**
     * Empty the buffer.
     */
    fun free()

    fun readBytes(length: Int = remaining()): ByteArray {
        val ret = ByteArray(length)
        read(ret)
        return ret
    }

    fun peekBytes(size: Int): ByteArray
    fun readAll(): Array<ByteBuffer>

    fun read(bytes: ByteArray)
    fun read(bytes: ByteArray, offset: Int, length: Int)
    fun read(into: WritableBuffers, length: Int)

    /**
     * Read the buffer. Returns false if nothing was read due to the buffer being empty.
     * @param into
     * @return
     */
    fun read(into: WritableBuffers): Boolean

    /**
     * Fill the given buffer.
     * @param buffer
     */
    fun read(buffer: ByteBuffer)

    /**
     * Return all available data as a single buffer.
     * @return
     */
    fun readByteBuffer(): ByteBuffer

    /**
     * Return all available data as a single direct buffer.
     * @return
     */
    fun readDirectByteBuffer(): ByteBuffer

    /**
     * Read the first ByteBuffer
     */
    fun readFirst(): ByteBuffer
    fun readByteBuffer(length: Int): ByteBuffer
    fun readInt(): Int
    fun readByteChar(): Char
    fun readShort(): Short
    fun readByte(): Byte
    fun readLong(): Long
    fun readUtf8String(length: Int): String {
        return this.readBytes(length).decodeToString()
    }

    fun readUtf8String(): String {
        val ret = peekUtf8String()
        free()
        return ret
    }

    /**
     * Fill the given buffer with data, until the given sequence of bytes is found.
     * @param into
     * @param scan The byte sequence to find.
     * @return Returns true if the byte sequence was found, false if the buffer ends
     * before the sequence was found.
     * For example, if the this buffer ends on a partial byte sequence match, the partial sequence
     * will be left in the this buffer, and all data prior to that sequence will be filled into
     * the given buffer.
     */
    fun readScan(into: WritableBuffers, scan: ByteArray): Boolean

    open fun spewString() {
        println(this.peekUtf8String())
    }

    fun peekByteChar(): Char
    fun peekShort(): Short
    fun peek(): Byte
    fun peekInt(): Int
    fun peekLong(): Long
    fun peekUtf8String(): String
}
