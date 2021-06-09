package com.koushikdutta.scratch

import com.koushikdutta.scratch.TestUtils.Companion.networkContextTest
import com.koushikdutta.scratch.buffers.ByteBufferList
import kotlin.test.Test

class ReadmeTests {
//    @Test
    fun testEcho() = networkContextTest {
        val server = listen(5555)
        // this will accept sockets one at a time
        // and echo data back
        for (socket in server.accept()) {
            val buffer = ByteBufferList()
            while (socket.read(buffer)) {
                socket.write(buffer)
            }
        }
    }
}