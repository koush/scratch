package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferCommon
import com.koushikdutta.scratch.buffers.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ByteBufferTests {
    @Test
    fun testByteBuffer() {
        val buffer = ByteBufferCommon(ByteArray(100))
        buffer.put(1)
        buffer.putShort(12)
        buffer.putInt(10)
        buffer.order = ByteOrder.LITTLE_ENDIAN
        buffer.putLong(20)

        buffer.flip()

        buffer.order = ByteOrder.BIG_ENDIAN
        assertEquals(buffer.get(), 1)
        assertEquals(buffer.getShort(), 12)
        assertEquals(buffer.getInt(), 10)
        buffer.order = ByteOrder.LITTLE_ENDIAN
        assertEquals(buffer.getLong(), 20)
    }

    @Test
    fun testByteBuffer2() {
        val buffer = ByteBufferCommon(ByteArray(100))
        buffer.putInt(10)
        buffer.order = ByteOrder.LITTLE_ENDIAN
        buffer.putLong(20)
        buffer.flip()

        buffer.order = ByteOrder.BIG_ENDIAN
        assertEquals(buffer.getInt(0), 10)
        buffer.order = ByteOrder.LITTLE_ENDIAN
        assertEquals(buffer.getLong(4), 20)
    }

    @Test
    fun testByteBufferCopies() {
        val buffer = ByteBufferCommon(ByteArray(100))
        buffer.putInt(10)
        buffer.order = ByteOrder.LITTLE_ENDIAN
        buffer.putLong(20)
        assertEquals(buffer.remaining(), 88)
        buffer.flip()
        assertEquals(buffer.remaining(), 12)

        val buffer2 = ByteBufferCommon(ByteArray(100))
        buffer2.put(buffer)

        assertEquals(buffer2.remaining(), 88)
        assertEquals(buffer.remaining(), 0)

        for (i in 0 until 12) {
            assertEquals(buffer.array()[i], buffer2.array()[i])
        }
    }

    @Test
    fun testByteArrayCopies() {
        val bytes = byteArrayOf(0, 1, 2, 3, 4, 5, 6)
        val buffer = ByteBufferCommon(ByteArray(100))
        buffer.put(bytes)

        for (i in 0 .. 6) {
            assertEquals(buffer.array()[i], i.toByte())
        }

        assertEquals(buffer.remaining(), 93)
    }

    @Test
    fun testByteArrayGet() {
        val buffer = ByteBufferCommon(byteArrayOf(0, 1, 2, 3))
        val bytes = ByteArray(2)
        buffer.get(bytes)
        assertEquals(bytes[0], 0)
        assertEquals(bytes[1], 1)
        buffer.get(bytes, 1, 1)
        assertEquals(bytes[1], 2)
    }
}
