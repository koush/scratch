package com.koushikdutta.scratch.buffers

class ByteBufferWrapper(val buffer: ByteBuffer): WritableBuffers {
    override fun order(): ByteOrder {
        return buffer.byteOrder()
    }

    override fun order(order: ByteOrder) {
        buffer.order(order)
    }

    override fun add(b: ReadableBuffers): WritableBuffers {
        b.readBuffers {
            for (b in it) {
                buffer.put(b)
            }
        }
        return this
    }

    override fun add(b: ByteBuffer): WritableBuffers {
        buffer.put(b)
        return this
    }

    override fun put(b: Byte): WritableBuffers {
        buffer.put(b)
        return this
    }

    override fun putBytes(bytes: ByteArray): WritableBuffers {
        buffer.put(bytes)
        return this
    }

    override fun putShort(s: Short): WritableBuffers {
        buffer.putShort(s)
        return this
    }

    override fun putInt(i: Int): WritableBuffers {
        buffer.putInt(i)
        return this
    }

    override fun putLong(l: Long): WritableBuffers {
        buffer.putLong(l)
        return this
    }

    override fun putByteChar(c: Char): WritableBuffers {
        buffer.put(c.toByte())
        return this
    }

    override fun <T> putAllocatedBytes(allocate: Int, writer: BuffersArrayWriter<T>): T {
        if (allocate > available())
            throw IllegalStateException("not enough space available in fixed size buffer")
        if (buffer.isDirect())
            throw IllegalStateException("fixed size buffer is a direct buffer")
        return writer(buffer.array(), buffer.arrayOffset() + buffer.position())
    }

    override fun <T> putAllocatedBuffer(allocate: Int, writer: BuffersBufferWriter<T>): T {
        if (allocate > available())
            throw IllegalStateException("not enough space available in fixed size buffer")
        return writer(buffer)
    }

    override fun <T> putAllocatedByteBuffer(allocate: Int, writer: BuffersBufferWriter<T>): T {
        if (buffer.isDirect())
            throw IllegalStateException("fixed size buffer is a direct buffer")
        return putAllocatedBuffer(allocate, writer)
    }

    override fun <T> putAllocatedBuffers(allocate: Int, writer: BuffersBuffersWriter<T>): T {
        if (allocate > available())
            throw IllegalStateException("not enough space available in fixed size buffer")
        return writer(arrayOf(buffer))
    }

    override fun putUtf8String(s: String): WritableBuffers {
        return putBytes(s.encodeToByteArray())
    }

    override fun remaining(): Int {
        return buffer.remaining()
    }

    fun available(): Int {
        return buffer.limit() - buffer.position()
    }

    override fun reclaim(vararg buffers: ByteBuffer?) {
    }

    override fun obtain(size: Int): ByteBuffer {
        throw IllegalStateException("can not call obtain on fixed buffer")
    }

    override fun giveReclaimedBuffers(into: ArrayList<ByteBuffer>) {
    }

    override fun takeReclaimedBuffers(from: AllocatingBuffers) {
    }
}