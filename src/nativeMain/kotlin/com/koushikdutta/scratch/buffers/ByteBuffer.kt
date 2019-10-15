package com.koushikdutta.scratch.buffers

actual typealias Buffer = BufferCommon
actual typealias ByteBuffer = ByteBufferCommonBase

actual fun ByteBuffer.order(): ByteOrder {
    return order
}
actual fun ByteBuffer.order(order: ByteOrder): ByteBuffer {
    this.order = order
    return this
}

actual fun createByteBuffer(array: ByteArray, offset: Int, length: Int): ByteBuffer {
    return ByteBufferCommon(array, offset, length)
}

actual fun allocateByteBuffer(length: Int): ByteBuffer {
    return ByteBufferCommon(ByteArray(length), 0, length)
}
