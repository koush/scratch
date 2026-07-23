package com.koushikdutta.scratch.buffers

typealias Buffer = java.nio.Buffer
typealias ByteBuffer = java.nio.ByteBuffer

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

private fun java.nio.ByteOrder.toByteOrder(): ByteOrder {
    return if (this == java.nio.ByteOrder.BIG_ENDIAN) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
}

private fun ByteOrder.toByteOrder(): java.nio.ByteOrder {
    return if (this == ByteOrder.BIG_ENDIAN) java.nio.ByteOrder.BIG_ENDIAN else java.nio.ByteOrder.LITTLE_ENDIAN
}

fun ByteBuffer.order(): ByteOrder = order().toByteOrder()
fun ByteBuffer.order(order: ByteOrder): ByteBuffer = order(order.toByteOrder())
fun ByteBuffer.duplicate(): ByteBuffer = this.duplicate()
fun ByteBuffer.byteOrder(): ByteOrder = order().toByteOrder()

fun createByteBuffer(array: ByteArray, offset: Int, length: Int): ByteBuffer = ByteBuffer.wrap(array, offset, length)
fun createByteBuffer(array: ByteArray): ByteBuffer {
    return createByteBuffer(array, 0, array.size)
}
fun allocateByteBuffer(length: Int): ByteBuffer = ByteBuffer.allocate(length)
fun allocateDirectByteBuffer(length: Int): ByteBuffer = ByteBuffer.allocateDirect(length)
