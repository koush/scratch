package com.koushikdutta.scratch

import com.koushikdutta.scratch.async.UnhandledAsyncExceptionError
import com.koushikdutta.scratch.async.async
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.event.AsyncEventLoop
import com.koushikdutta.scratch.http.client.createContentLengthPipe
import com.koushikdutta.scratch.http.client.executor.AsyncHttpConnectSocketExecutor
import com.koushikdutta.scratch.http.server.AsyncHttpServer
import kotlin.random.Random
import kotlin.test.assertTrue

internal class ExpectedException: Exception()
internal class TimeoutException: Exception()

class TestUtils {
    companion object {
        fun networkContextTest(failureExpected: Boolean = false, runner: suspend AsyncEventLoop.() -> Unit) {
            val networkContext = AsyncEventLoop()

            val result = networkContext.async {
                runner(networkContext)
            }
            result.finally {
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
            catch (exception: UnhandledAsyncExceptionError) {
                assertTrue(failureExpected)
            }
            catch (exception: ExpectedException) {
                assertTrue(failureExpected)
            }
        }

        fun createRandomRead(length: Int): AsyncRead {
            return createUnboundRandomRead().pipe(createContentLengthPipe(length.toLong()))
        }

        fun createUnboundRandomRead(): AsyncReader {
            val random = Random
            return AsyncReader {
                it.putAllocatedBytes(10000) { bytes: ByteArray, offset: Int ->
                    random.nextBytes(bytes, offset, offset + 10000)
                }
                true
            }
        }

        suspend fun AsyncRead.countBytes(): Int {
            var ret = 0
            val buf = ByteBufferList()
            while (this(buf)) {
                ret += buf.remaining()
                buf.free()
            }
            return ret
        }

        suspend fun AsyncSocket.countBytes(): Int {
            var ret = 0
            val buf = ByteBufferList()
            while (read(buf)) {
                ret += buf.remaining()
                buf.free()
            }
            return ret
        }
    }
}

suspend fun Collection<Promise<*>>.awaitAll() {
    for (promise in this) {
        promise.await()
    }
}

internal fun AsyncHttpServer.createFallbackClient(): AsyncHttpConnectSocketExecutor {
    val pipeServer = AsyncPipeServerSocket()
    listen(pipeServer)
    return AsyncHttpConnectSocketExecutor {
        pipeServer.connect()
    }
}

