package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.parser.readAllString
import org.junit.Test

class PipeTests {
    @Test
    fun testPipeServer() {
        val server = createAsyncPipeServerSocket()

        var data = ""
        server.accept().receive {
            async {
                data = readAllString(it::read)
            }
        }

        async {
            val client = server.connect()
            client.write(ByteBufferList().putString("hello world"))
            client.close()
        }

        assert(data == "hello world")
    }
}
