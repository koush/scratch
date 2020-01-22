package com.koushikdutta.scratch.buffers

expect fun ByteBuffer.order(): ByteOrder
expect fun ByteBuffer.order(order: ByteOrder): ByteBuffer
fun createByteBuffer(array: ByteArray): ByteBuffer {
    return createByteBuffer(array, 0, array.size)
}
expect fun createByteBuffer(array: ByteArray, offset: Int, length: Int): ByteBuffer
expect fun allocateByteBuffer(length: Int): ByteBuffer

enum class ByteOrder {
    LITTLE_ENDIAN {
        override fun getNumber(bytes: ByteArray, offset: Int, length: Int): Long {
            var ret: Long = 0L
            for (i in offset + length - 1 downTo offset) {
                ret = ret shl 8
                ret = ret or (0xFF and bytes[i].toInt()).toLong()
            }
            return ret
        }

        override fun setNumber(bytes: ByteArray, offset: Int, length: Int, value: Long) {
            var value = value
            for (i in offset until offset + length) {
                val byte = (value and 0xFF).toByte()
                bytes[i] = byte
                value = value shr 8
            }
        }
    },
    BIG_ENDIAN {
        override fun getNumber(bytes: ByteArray, offset: Int, length: Int): Long {
            var ret: Long = 0L
            for (i in offset until offset + length) {
                ret = ret shl 8
                ret = ret or (0xFF and bytes[i].toInt()).toLong()
            }
            return ret
        }

        override fun setNumber(bytes: ByteArray, offset: Int, length: Int, value: Long) {
            var value = value
            for (i in offset + length - 1 downTo offset) {
                val byte = (value and 0xFF).toByte()
                bytes[i] = byte
                value = value shr 8
            }
        }
    };

    abstract fun getNumber(bytes: ByteArray, offset: Int, length: Int): Long
    abstract fun setNumber(bytes: ByteArray, offset: Int, length: Int, value: Long)
}

expect abstract class Buffer {
    fun clear(): Buffer
    fun position(position: Int): Buffer
    fun flip(): Buffer
    fun position(): Int
    fun capacity(): Int
    fun mark(): Buffer
    fun limit(): Int
    fun limit(limit: Int): Buffer
    fun hasRemaining(): Boolean
    fun remaining(): Int
    fun reset(): Buffer
    abstract fun array(): Any
    abstract fun arrayOffset(): Int
    abstract fun isDirect(): Boolean
}

expect abstract class ByteBuffer : Buffer {
    abstract fun getLong(offset: Int): Long
    abstract fun getInt(offset: Int): Int
    abstract fun getShort(offset: Int): Short
    abstract fun get(offset: Int): Byte

    abstract fun getLong(): Long
    abstract fun getInt(): Int
    abstract fun getShort(): Short
    abstract fun get(): Byte
    fun get(dst: ByteArray, offset: Int, length: Int): ByteBuffer
    fun get(dst: ByteArray): ByteBuffer

    fun put(byteBuffer: ByteBuffer): ByteBuffer
    fun put(bytes: ByteArray): ByteBuffer
    fun put(bytes: ByteArray, offset: Int, length: Int): ByteBuffer
    abstract fun put(value: Byte): ByteBuffer
    abstract fun putShort(value: Short): ByteBuffer
    abstract fun putInt(value: Int): ByteBuffer
    abstract fun putLong(value: Long): ByteBuffer

    final override fun array(): ByteArray
    abstract fun duplicate(): ByteBuffer
}

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

    var order: ByteOrder = ByteOrder.BIG_ENDIAN

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
    fun put(bytes: ByteArray): ByteBufferCommonBase {
        ensureRemaining(bytes.size)
        bytes.copyInto(this.bytes!!, arrayOffsetPosition, 0, bytes.size)
        position += bytes.size
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