package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.parser.readAllString
import org.junit.Test

class NioTests {
    @Test
    fun testNioWriter() {
        var highWater = false
        val pipe = object : NonBlockingWritePipe(0) {
            override fun writable() {
                highWater = true
            }
        }

        val keepGoing = pipe.write(ByteBufferList().putString("Hello World"))
        pipe.write(ByteBufferList().putString("Hello World"))
        pipe.write(ByteBufferList().putString("Hello World"))
        pipe.end()

        var data = ""
        async {
            data = readAllString(pipe::read)
        }

        assert(data == "Hello WorldHello WorldHello World")

        assert(!keepGoing)
        assert(highWater)
    }

    @Test
    fun testNioWriterWritable() {
        val yielder = Cooperator()
        val pipe = object : NonBlockingWritePipe(0) {
            override fun writable() {
                yielder.resume()
            }
        }

        async {
            for (i in 1..3) {
                val keepGoing = pipe.write(ByteBufferList().putString("Hello World"))
                assert(!keepGoing)
                yielder.yield()
            }
            pipe.end()
        }

        var data = ""
        async {
            data = readAllString(pipe::read)
        }

        assert(data == "Hello WorldHello WorldHello World")
    }
}