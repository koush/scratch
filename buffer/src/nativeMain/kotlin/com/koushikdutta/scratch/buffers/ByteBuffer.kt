package com.koushikdutta.scratch.buffers

abstract class BufferCommon(internal val capacity: Int) {
    protected var position = 0
    fun position(position: Int): BufferCommon {
        this.position = position
        return this
    }

    protected var limit = capacity
    fun flip(): BufferCommon {
        limit = position
        position = 0
        return this
    }
    fun position(): Int {
        return position
    }
    fun capacity(): Int {
        return capacity
    }
    protected var mark = 0
    fun mark(): BufferCommon {
        mark = position
        return this
    }
    fun limit(): Int {
        return limit
    }
    fun limit(limit: Int): BufferCommon {
        this.limit = limit
        return this
    }
    fun hasRemaining(): Boolean {
        return remaining() != 0
    }
    fun remaining(): Int {
        return limit - position
    }
    fun reset(): BufferCommon {
        this.position = mark
        return this
    }
    fun clear(): BufferCommon {
        this.position = 0
        this.limit = capacity
        return this
    }
    abstract fun array(): Any
    abstract fun arrayOffset(): Int
    abstract fun isDirect(): Boolean
}

abstract class ByteBufferCommonBase(protected var bytes: ByteArray? = null, protected var arrayOffset: Int?, capacity: Int) : BufferCommon(capacity) {
    abstract fun getInt(offset: Int): Int
    abstract fun getShort(offset: Int): Short
    abstract fun get(offset: Int): Byte
    abstract fun getLong(offset: Int): Long

    abstract fun getLong(): Long
    abstract fun getInt(): Int
    abstract fun getShort(): Short
    abstract fun get(): Byte
    open fun get(dst: ByteArray, offset: Int, length: Int): ByteBufferCommonBase {
        ensureRemaining(length)
        val start = arrayOffsetPosition
        val end = arrayOffsetPosition + length
        bytes!!.copyInto(dst, offset, start, end)
        position += length
        return this
    }
    fun get(dst: ByteArray): ByteBufferCommonBase {
        return get(dst, 0, dst.size)
    }

    var order: ByteOrder =
        ByteOrder.BIG_ENDIAN

    protected fun ensureRemaining(need: Int) {
        if (position + need > capacity)
            throw Exception("Buffer Overflow position: $position need: $need capacity: $capacity")
        if (position + need > limit)
            throw Exception("Buffer Limit position: $position need: $need limit: $limit")
    }

    internal inline val arrayOffsetPosition: Int
        get() = arrayOffset!! + position

    fun put(byteBuffer: ByteBufferCommonBase): ByteBufferCommonBase {
        ensureRemaining(byteBuffer.remaining())

        val length = byteBuffer.remaining()
        val start = byteBuffer.arrayOffsetPosition
        byteBuffer.bytes!!.copyInto(bytes!!, arrayOffsetPosition, start, start + length)
        position += length
        byteBuffer.position += length
        return this
    }
    fun put(bytes: ByteArray): ByteBufferCommonBase = put(bytes, 0, bytes.size)
    fun put(bytes: ByteArray, offset: Int, length: Int): ByteBufferCommonBase {
        ensureRemaining(length)
        bytes.copyInto(this.bytes!!, arrayOffsetPosition, offset, offset + length)
        position += length
        return this
    }

    abstract fun put(value: Byte): ByteBufferCommonBase
    abstract fun putShort(value: Short): ByteBufferCommonBase
    abstract fun putInt(value: Int): ByteBufferCommonBase
    abstract fun putLong(value: Long): ByteBufferCommonBase

    final override fun array(): ByteArray {
        return bytes!!
    }
    abstract fun duplicate(): ByteBufferCommonBase

    override fun arrayOffset(): Int {
        return arrayOffset!!
    }
}

open class ByteBufferCommon(bytes: ByteArray? = null, arrayOffset: Int?, capacity: Int) : ByteBufferCommonBase(bytes, arrayOffset, capacity) {
    constructor(bytes: ByteArray, arrayOffset: Int = 0, capacity: Int = bytes.size - arrayOffset) : this(bytes as ByteArray?, arrayOffset, capacity)

    init {
        if (bytes != null && bytes.size < arrayOffset!! + capacity)
            throw Exception("invalid array size")
    }

    protected fun getNumber(length: Int): Long {
        ensureRemaining(length)
        val ret = order.getNumber(bytes!!, arrayOffsetPosition, length)
        position += length
        return ret
    }

    protected fun getNumber(offset: Int, length: Int): Long {
        if (offset > capacity || offset < 0)
            throw Exception("Buffer Overflow position: $position limit: $limit offset: $offset length: $length capacity: $capacity")
        return order.getNumber(bytes!!, arrayOffset!! + offset, length)
    }

    protected fun putNumber(length: Int, value: Long): ByteBufferCommon {
        ensureRemaining(length)
        order.setNumber(bytes!!, arrayOffsetPosition, length, value)
        position += length
        return this
    }

    protected fun putNumber(offset: Int, length: Int, value: Long): ByteBufferCommon {
        if (offset > capacity || offset < 0)
            throw Exception("Buffer Overflow position: $position limit: $limit offset: $offset length: $length capacity: $capacity")
        order.setNumber(bytes!!, arrayOffset!! + offset, length, value)
        return this
    }

    override fun isDirect(): Boolean {
        return false
    }

    override fun getInt(offset: Int): Int {
        return getNumber(offset, 4).toInt()
    }

    override fun getShort(offset: Int): Short {
        return getNumber(offset, 2).toShort()
    }

    override fun get(offset: Int): Byte {
        return getNumber(offset, 1).toByte()
    }

    override fun getLong(offset: Int): Long {
        return getNumber(offset, 8)
    }

    override fun getLong(): Long {
        return getNumber(8).toLong()
    }

    override fun getInt(): Int {
        return getNumber(4).toInt()
    }

    override fun getShort(): Short {
        return getNumber(2).toShort()
    }

    override fun get(): Byte {
        return getNumber(1).toByte()
    }

    override fun put(value: Byte): ByteBufferCommonBase {
        return putNumber(1, value.toLong())
    }

    override fun putShort(value: Short): ByteBufferCommonBase {
        return putNumber(2, value.toLong())
    }

    override fun putInt(value: Int): ByteBufferCommon {
        return putNumber(4, value.toLong())
    }

    override fun putLong(value: Long): ByteBufferCommonBase {
        return putNumber(8, value)

    }

    override fun duplicate(): ByteBufferCommon {
        val ret = ByteBufferCommon(bytes, arrayOffset, capacity)
        ret.order = order
        ret.position = position
        ret.limit = limit
        ret.mark = mark
        return ret
    }
}
actual typealias Buffer = BufferCommon
actual typealias ByteBuffer = ByteBufferCommonBase

actual fun ByteBuffer.order() = order
actual fun ByteBuffer.order(order: ByteOrder): ByteBuffer {
    this.order = order
    return this
}
actual fun ByteBuffer.duplicate() = duplicate()
actual fun ByteBuffer.byteOrder() = order

actual fun createByteBuffer(array: ByteArray, offset: Int, length: Int): ByteBuffer {
    return ByteBufferCommon(array, offset, length)
}

actual fun allocateByteBuffer(length: Int): ByteBuffer {
    return ByteBufferCommon(ByteArray(length), 0, length)
}

actual fun allocateDirectByteBuffer(length: Int) = allocateByteBuffer(length)
