package com.koushikdutta.scratch.buffers

typealias BuffersArrayWriter = (array: ByteArray, startOffset: Int) -> Unit
typealias BuffersBufferWriter<T> = (buffer: ByteBuffer) -> T

interface WritableBuffers : AllocatingBuffers {
    fun order(): ByteOrder
    fun order(order: ByteOrder)

    fun add(b: ReadableBuffers): WritableBuffers
    fun add(b: ByteBuffer): WritableBuffers
    fun addAll(vararg bb: ByteBuffer): WritableBuffers {
        for (b in bb) {
            add(b)
        }
        return this
    }

    fun add(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): WritableBuffers {
        return add(createByteBuffer(bytes, offset, length))
    }

    fun put(b: Byte): WritableBuffers
    fun putBytes(bytes: ByteArray): WritableBuffers
    fun putShort(s: Short): WritableBuffers
    fun putInt(i: Int): WritableBuffers
    fun putLong(l: Long): WritableBuffers
    fun putByteChar(c: Char): WritableBuffers
    fun putAllocatedBytes(allocate: Int, writer: BuffersArrayWriter): WritableBuffers
    fun <T> putAllocatedBuffer(allocate: Int, writer: BuffersBufferWriter<T>): T
    fun <T> putAllocatedByteBuffer(allocate: Int, writer: BuffersBufferWriter<T>): T
    fun putUtf8String(s: String): WritableBuffers

    fun remaining(): Int
}
