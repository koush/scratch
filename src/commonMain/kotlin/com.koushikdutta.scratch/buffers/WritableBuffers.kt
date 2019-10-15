package com.koushikdutta.scratch.buffers

interface WritableBuffers {
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

    fun putUtf8String(s: String): WritableBuffers
}
