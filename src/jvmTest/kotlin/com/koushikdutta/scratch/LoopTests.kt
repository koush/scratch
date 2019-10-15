package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.event.AsyncEventLoop
import org.junit.Test
import java.util.concurrent.TimeoutException
import kotlin.coroutines.*

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

        networkContext.postDelayed(1000) {
            result.setComplete(Result.failure(TimeoutException()))
            networkContext.stop()
        }

        try {
            networkContext.run()
            result.rethrow()
        }
        catch (exception: Throwable) {
            if (!failureExpected || exception is TimeoutException)
                throw exception
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
            assert(false)
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

        networkContext.postDelayed(1000) {
            result.setComplete(Result.failure(TimeoutException()))
            networkContext.stop()
        }

        networkContext.run()
        try {
            result.rethrow()
            assert(result.value == 42)
        }
        catch (exception: Exception) {
            assert(false)
        }
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
        val reader = AsyncReader(client::read)
        assert(reader.readUtf8String(6) == "hello!")
    }

    @Test
    fun testServerCrash()= networkContextTest(true) {
            val server = listen()
            server.accept().receive {
                throw Exception()
            }
            val client = connect("127.0.0.1", server.localPort)
            client.write(ByteBufferList().putUtf8String("hello!"))
            val reader = AsyncReader(client::read)
            reader.readUtf8String(1)
            assert(false)
    }
}
