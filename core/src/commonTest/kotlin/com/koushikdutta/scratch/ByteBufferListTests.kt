package com.koushikdutta.scratch

import com.koushikdutta.scratch.TestUtils.Companion.countBytes
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.filters.ChunkedInputPipe
import com.koushikdutta.scratch.filters.ChunkedOutputPipe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ByteBufferListTests {
    @Test
    fun testWriter() {
        val buf = ByteBufferList()

        buf.putAllocatedBuffer(10000) {
            it.put(ByteArray(4999))
        }

        assertEquals(4999, buf.remaining())

        buf.putAllocatedBuffer(1000) {
            it.put(ByteArray(999))
        }

        assertEquals(4999 + 999, buf.remaining())
    }

    @Test
    fun testByteBufferAllocations() {
        var done = false
        val start = ByteBufferList.totalObtained
        async {
            val chunked = TestUtils.createRandomRead(1000000)
                .pipe(ChunkedOutputPipe)
            val length = AsyncReader(chunked)
                .pipe(ChunkedInputPipe)
                .countBytes()

            // another read should still indicate eos
            assertEquals(length, 1000000)
            done = true
        }
        assertTrue(done)
        assertTrue(ByteBufferList.totalObtained - start < 60000)
    }
}