package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.net.AsyncNetworkContext
import org.junit.Test
import java.lang.Exception
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class NetTests {
    private fun networkContextTest(failureExpected: Boolean = false, runner: suspend AsyncNetworkContext.() -> Unit) {
        val networkContext = AsyncNetworkContext()
        val semaphore = Semaphore(0)

        val result = networkContext.async {
            try {
                runner(networkContext)
            }
            finally {
                semaphore.release()
            }
        }
        .catch {
            // ignore errors, rethrow on the test main thread
        }

        networkContext.postDelayed(1000) {
            result.setComplete(Result.failure(TimeoutException()))
            semaphore.release()
        }

        networkContext.affinity?.setUncaughtExceptionHandler { thread: Thread, throwable: Throwable ->
            result.setComplete(Result.failure(throwable))
            semaphore.release()
        }

        while (true) {
            if (semaphore.tryAcquire(1000, TimeUnit.MILLISECONDS))
                break
            if (!networkContext.affinity!!.isAlive)
                throw Exception("network context crash?")
        }
        networkContext.kill()
        if (failureExpected) {
            try {
                result.rethrow()
                assert(false)
            }
            catch (exception: Exception) {
            }
        }
        else {
            result.rethrow()
        }
    }


    @Test
    fun testAsyncException() {
        val networkContext = AsyncNetworkContext()
        val semaphore = Semaphore(0)

        val result = networkContext.async {
            try {
                throw Exception()
            }
            finally {
                semaphore.release()
            }
        }
        .catch {
        }

        networkContext.postDelayed(1000) {
            result.setComplete(Result.failure(TimeoutException()))
            semaphore.release()
        }

        semaphore.acquire()
        networkContext.kill()
        try {
            result.rethrow()
            assert(false)
        }
        catch (exception: Exception) {
        }
    }


    @Test
    fun testAsyncSuccess() {
        val networkContext = AsyncNetworkContext()
        val semaphore = Semaphore(0)

        val result = networkContext.async {
            try {
                42
            }
            finally {
                semaphore.release()
            }
        }

        networkContext.postDelayed(1000) {
            result.setComplete(Result.failure(TimeoutException()))
            semaphore.release()
        }

        semaphore.acquire()
        networkContext.kill()
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
        client.write(ByteBufferList().putString("hello!"))
        val reader = AsyncReader(client::read)
        assert(reader.readString(6) == "hello!")
    }

    @Test
    fun testServerCrash() = networkContextTest(true) {
        val server = listen()
        server.accept().receive {
            throw Exception()
        }
        val client = connect("127.0.0.1", server.localPort)
        client.write(ByteBufferList().putString("hello!"))
        val reader = AsyncReader(client::read)
        reader.readString(1)
        assert(false)
    }
}
