package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import kotlin.test.Test
import kotlin.test.assertTrue

class DoubleReadTests {
    fun testDoubleRead(read: AsyncRead, other: AsyncRead = read) {
        var cancelled = false
        async {
            try {
                read(ByteBufferList())
            }
            catch (throwable: Throwable) {
                cancelled = true
            }
        }

        async {
            other(ByteBufferList())
        }

        assertTrue(cancelled)
    }

    @Test
    fun testPipeDoubleRead() {
        val pipe = PipeSocket()

        testDoubleRead(pipe)
    }

    @Test
    fun testInterruptDoubleRead() {
        val pipe = PipeSocket()

        val i1 = InterruptibleRead(pipe)
        val i2 = InterruptibleRead(pipe)

        testDoubleRead(i1, i2)
    }


    @Test
    fun testInterruptDoubleRead2() {
        val pipe = PipeSocket()

        val i1 = InterruptibleRead(pipe)
        testDoubleRead(i1)
    }

    @Test
    fun testPipedDoubleRead() {
        val pipe = PipeSocket()
        val piped = pipe.pipe {
            while (it(buffer))
                flush()
        }

        testDoubleRead(piped, piped)
    }
}
