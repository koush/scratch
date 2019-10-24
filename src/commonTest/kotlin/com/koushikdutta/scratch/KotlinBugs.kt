package com.koushikdutta.scratch

import com.koushikdutta.scratch.TestUtils.Companion.countBytes
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.event.AsyncEventLoop
import com.koushikdutta.scratch.event.connect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.random.Random

class KotlinBugs {
    private fun networkContextTest(failureExpected: Boolean = false, runner: suspend AsyncEventLoop.() -> Unit) {
        val networkContext = AsyncEventLoop()

        val result = networkContext.async {
            runner(networkContext)
        }
        result.setCallback {
            networkContext.stop()
        }

        networkContext.postDelayed(1000000) {
            throw TimeoutException()
        }

        try {
            networkContext.run()
            result.rethrow()
            assertTrue(!failureExpected)
        }
        catch (exception: ExpectedException) {
            assertTrue(failureExpected)
        }
    }

    @Test
    fun testServerALot() = networkContextTest {
        val server = listen(0)

        server.acceptAsync {
            val buffer = ByteBufferList()
            val random = TestUtils.createRandomRead(1000000)
            while (random(buffer)) {
                write(buffer)
                assertTrue(buffer.isEmpty)
            }
            close()
        }

        val runs = 5
        // this captured variable does not get seem to get updated properly
        var count = 0
        (1..runs).map {
            async {
                val broken = true
                if (broken) {
                    count += connect("127.0.0.1", server.localPort).countBytes()
                }
                else {
                    val read = connect("127.0.0.1", server.localPort).countBytes()
                    count += read
                }
            }
        }
        .awaitAll()

        assertEquals(count, 1000000 * runs)
    }


    @Test
    fun testSocketsALotLeaveThemOpen() = networkContextTest{
        val server = listen(0, null, 10000)
        val broken = true
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
