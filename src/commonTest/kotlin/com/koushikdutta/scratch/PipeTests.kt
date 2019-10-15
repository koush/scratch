package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.parser.readAllString
import kotlin.test.Test
import kotlin.test.assertEquals

class PipeTests {
    @Test
    fun testPipeServer() {
        val server = createAsyncPipeServerSocket()

        var data = ""
        server.accept().receive {
            val readRef: AsyncRead = {read(it)}
            data = readAllString(readRef)
        }

        async {
            val client = server.connect()
            client.write(ByteBufferList().putUtf8String("hello world"))
            client.close()
        }

        assertEquals(data, "hello world")
    }
}
