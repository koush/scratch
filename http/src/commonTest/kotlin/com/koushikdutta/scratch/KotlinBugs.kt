package com.koushikdutta.scratch

import com.koushikdutta.scratch.TestUtils.Companion.countBytes
import com.koushikdutta.scratch.TestUtils.Companion.networkContextTest
import com.koushikdutta.scratch.async.async
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.event.AsyncEventLoop
import com.koushikdutta.scratch.event.connect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.random.Random

class KotlinBugs {
    @Test
    fun testCapturedVariableIncrementInSuspend() = networkContextTest {
        val server = listen(0)

        server.acceptAsync {
            val buffer = ByteBufferList()
            val random = TestUtils.createRandomRead(1000000)
            while (random(buffer)) {
                drain(buffer)
                assertTrue(buffer.isEmpty)
            }
            close()
        }

        val runs = 5
        // this captured variable does not get seem to get updated properly
        var count = 0
        (1..runs).map {
            async {
                val broken = false
                if (broken) {
                    count += connect("127.0.0.1", server.localPort).countBytes()
                    println("looped")
                }
                else {
                    val read = connect("127.0.0.1", server.localPort).countBytes()
                    count += read
                }
            }
            .asPromise()
        }
        .awaitAll()

        assertEquals(count, 1000000 * runs)
    }


    @Test
    fun testChainingSuspendSocketClose() = networkContextTest{
        val server = listen(0, null, 10000)
        val broken = false
        if (broken) {
            connect("127.0.0.1", server.localPort).close()
        }
        else {
            val socket = connect("127.0.0.1", server.localPort)
            socket.close()
        }
        assertTrue(true)
    }

}
