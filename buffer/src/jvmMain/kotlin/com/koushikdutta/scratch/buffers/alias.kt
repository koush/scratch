package com.koushikdutta.scratch.buffers

actual typealias Buffer = java.nio.Buffer
actual typealias ByteBuffer = java.nio.ByteBuffer

private fun java.nio.ByteOrder.toByteOrder(): ByteOrder {
    return if (this == java.nio.ByteOrder.BIG_ENDIAN) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
}

private fun ByteOrder.toByteOrder(): java.nio.ByteOrder {
    return if (this == ByteOrder.BIG_ENDIAN) java.nio.ByteOrder.BIG_ENDIAN else java.nio.ByteOrder.LITTLE_ENDIAN
}

actual fun ByteBuffer.order(): ByteOrder {
    return order().toByteOrder()
}

actual fun ByteBuffer.order(order: ByteOrder): ByteBuffer {
    return order(order.toByteOrder())
}

actual fun createByteBuffer(array: ByteArray, offset: Int, length: Int): ByteBuffer = ByteBuffer.wrap(array, offset, length)
actual fun allocateByteBuffer(length: Int) = ByteBuffer.allocate(length)
actual fun allocateDirectByteBuffer(length: Int) = ByteBuffer.allocateDirect(length)
