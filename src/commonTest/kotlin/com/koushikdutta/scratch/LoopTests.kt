package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.event.AsyncEventLoop
import com.koushikdutta.scratch.event.connect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

private class TimeoutException: Exception()
private class ExpectedException: Exception()

class LoopTests {
    private fun networkContextTest(failureExpected: Boolean = false, runner: suspend AsyncEventLoop.() -> Unit) {
        val networkContext = AsyncEventLoop()

        val result = networkContext.async {
            try {
                runner(networkContext)
            }
            finally {
                networkContext.stop()
            }
        }

        networkContext.postDelayed(100000000) {
            result.setComplete(Result.failure(TimeoutException()))
            networkContext.stop()
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
    fun testPostDelayed() {
        val networkContext = AsyncEventLoop()

        val result = networkContext.async {
            try {
                sleep(500)
                throw Exception()
            }
            finally {
                networkContext.stop()
            }
        }

        networkContext.postDelayed(3000) {
            result.setComplete(Result.failure(Exception()))
            networkContext.stop()
        }

        try {
            networkContext.run()
            result.rethrow()
            fail("exception expected")
        }
        catch (exception: Exception) {
        }
    }


    @Test
    fun testAsyncException() {
        val networkContext = AsyncEventLoop()

        val result = networkContext.async {
            try {
                throw Exception()
            }
            finally {
                networkContext.stop()
            }
        }

        networkContext.postDelayed(1000) {
            result.setComplete(Result.failure(TimeoutException()))
            networkContext.stop()
        }

        try {
            networkContext.run()
            result.rethrow()
            fail("exception expected")
        }
        catch (exception: Exception) {
        }
    }


    @Test
    fun testAsyncSuccess() {
        val networkContext = AsyncEventLoop()

        val result = networkContext.async {
            try {
                42
            }
            finally {
                networkContext.stop()
            }
        }

        networkContext.postDelayed(10000000) {
            result.setComplete(Result.failure(TimeoutException()))
            networkContext.stop()
        }

        networkContext.run()
        result.rethrow()
        assertEquals(result.value, 42)
    }

    @Test
    fun testEchoServer() = networkContextTest {
        val server = listen()
        server.accept().receive {
            val buffer = ByteBufferList()
            while (read(buffer)) {
                write(buffer)
            }
        }
        val client = connect("127.0.0.1", server.localPort)
        client.write(ByteBufferList().putUtf8String("hello!"))
        val reader = AsyncReader({client.read(it)})
        assertEquals(reader.readUtf8String(6), "hello!")
    }

    @Test
    fun testServerCrash() = networkContextTest(true) {
        val server = listen()
        server.accept().receive {
            throw ExpectedException()
        }
        val client = connect("127.0.0.1", server.localPort)
        client.write(ByteBufferList().putUtf8String("hello!"))
        val reader = AsyncReader({client.read(it)})
        reader.readUtf8String(1)
        fail("exception expected")
    }

    @Test
    fun testReadException() = networkContextTest(true) {
        val server = listen()
        server.accept().receive {
            write(ByteBufferList().putUtf8String("hello"))
        }
        val client = connect("127.0.0.1", server.localPort)
        val buffer = ByteBufferList()
        client.read(buffer)
        throw ExpectedException()
    }
}
