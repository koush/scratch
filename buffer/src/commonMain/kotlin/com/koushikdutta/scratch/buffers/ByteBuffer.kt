package com.koushikdutta.scratch.buffers

expect fun ByteBuffer.order(): ByteOrder
expect fun ByteBuffer.order(order: ByteOrder): ByteBuffer
fun createByteBuffer(array: ByteArray): ByteBuffer {
    return createByteBuffer(array, 0, array.size)
}
expect fun createByteBuffer(array: ByteArray, offset: Int, length: Int): ByteBuffer
expect fun allocateByteBuffer(length: Int): ByteBuffer
expect fun allocateDirectByteBuffer(length: Int): ByteBuffer

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
            var tmp = value
            for (i in offset until offset + length) {
                val byte = (tmp and 0xFF).toByte()
                bytes[i] = byte
                tmp = tmp shr 8
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
            var tmp = value
            for (i in offset + length - 1 downTo offset) {
                val byte = (tmp and 0xFF).toByte()
                bytes[i] = byte
                tmp = tmp shr 8
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
