package com.koushikdutta.scratch

import com.koushikdutta.scratch.TestUtils.Companion.countBytes
import com.koushikdutta.scratch.async.UnhandledAsyncExceptionError
import com.koushikdutta.scratch.async.async
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.event.AsyncEventLoop
import com.koushikdutta.scratch.event.InetSocketAddress
import com.koushikdutta.scratch.event.connect
import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.AsyncHttpResponse
import com.koushikdutta.scratch.http.OK
import com.koushikdutta.scratch.http.body.BinaryBody
import com.koushikdutta.scratch.http.body.Utf8StringBody
import com.koushikdutta.scratch.http.client.AsyncHttpClient
import com.koushikdutta.scratch.http.client.middleware.createContentLengthPipe
import com.koushikdutta.scratch.http.server.AsyncHttpServer
import com.koushikdutta.scratch.parser.readAllString
import com.koushikdutta.scratch.uri.URI
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class LoopTests {
    private fun networkContextTest(failureExpected: Boolean = false, runner: suspend AsyncEventLoop.() -> Unit) {
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
            throw TimeoutException()
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
            throw TimeoutException()
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

        networkContext.postDelayed(1000) {
            throw TimeoutException()
        }

        networkContext.run()
        result.rethrow()
        assertEquals(result.getOrThrow(), 42)
    }

    @Test
    fun testEchoServer() = networkContextTest {
        val server = listen()
        server.acceptAsync {
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
    fun testServerNotCrash() = networkContextTest {
        val server = listen()
        server.acceptAsync {
            throw ExpectedException()
        }
        .closeOnError()
        val client = connect("127.0.0.1", server.localPort)
        try {
            client.write(ByteBufferList().putUtf8String("hello!"))
            val reader = AsyncReader({client.read(it)})
                reader.readUtf8String(1)
        }
        catch (exception: IOException) {
            return@networkContextTest
        }
        fail("IOException expected")
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

        var count = 0
        (0 until runs).map {
            async {
                val read = connect("127.0.0.1", server.localPort).countBytes()
                count += read
            }
        }
        .awaitAll()

        assertEquals(count, 1000000 * runs)
    }

    @Test
    fun testByteBufferAllocations() = networkContextTest {
        val start = ByteBufferList.totalObtained
        val server = listen(0)

        server.acceptAsync {
            TestUtils.createRandomRead(100000000).copy(::write)
            close()
        }

        val count  = async {
            connect("127.0.0.1", server.localPort).countBytes()
        }
        .await()

        assertEquals(count, 100000000)
        // socket should quickly hit a 64k read buffer max as the underlying allocator grows
        // to handle the data rate.
        // 10000 for write
        // 1024 + 2048 + 4096 + 8192 + 16384 + 32768 + 65536 = 130048
        // = 140048
        // but it ends up being slightly better since the min allocation is 8192 anyways, and the smaller
        // allocs are collapsed.
        assertTrue(ByteBufferList.totalObtained - start < 150000)
    }

    @Test
    fun testReadException() = networkContextTest(true) {
        val server = listen()
        server.acceptAsync {
            write(ByteBufferList().putUtf8String("hello"))
        }
        val client = connect("127.0.0.1", server.localPort)
        val buffer = ByteBufferList()
        client.read(buffer)
        throw ExpectedException()
    }

    @Test
    fun testSocketsALotLeaveThemOpen() = networkContextTest{
        val server = listen(0, null, 10000)
        for (i in 1 until 5) {
            connect("127.0.0.1", server.localPort)
        }
        assertTrue(true)
    }

    @Test
    fun testSocketsALotConcurrent() = networkContextTest{
        val server = listen(0, null, 10000)
        var resume: Continuation<Unit>? = null
        var connected = 0
        // there seems to be a weird issue with opening a ton of file descriptors too quickly.
        // the file limit is not hit, but connects will fail with "Connection reset by peer"
        // even though this code is leaving the connection open.
        // sticking a sleep in there seems to fix it.
        val connectCount = 5000
        val random = Random.Default
        for (i in 0 until connectCount) {
            // this test is flaky. connection resets if the system can't handle it.
            async {
                sleep(abs(random.nextLong()) % 5000)
                val socket = connect("127.0.0.1", server.localPort)
                sleep(10)
                socket.close()
                connected++
                if (connected == connectCount)
                    resume!!.resume(Unit)
            }
            .rethrow()
        }
        suspendCoroutine<Unit> {
            resume = it
        }
        assertTrue(true)
    }

    @Test
    fun testHttpALot() = networkContextTest {
        val postLength = 1000000
        val server = listen(0, null, 10000)
        val httpServer = AsyncHttpServer {
            // would be cool to pipe hte request right back to the response
            // without buffering, but the http spec does not work that way.
            // entire request must be received before sending a response.
            var len = 0
            val buf = ByteBufferList()
            while (it.body!!(buf)) {
                len += buf.remaining()
                buf.free()
            }
            assertEquals(postLength, len)
            AsyncHttpResponse.OK(body = Utf8StringBody("hello world"))
        }

        httpServer.listen(server)

        val numRequests = 1000
        var requestsCompleted = 0
        val httpClient = AsyncHttpClient(this)
        val random = Random.Default
        suspendCoroutine<Unit> { resume ->
            for (i in 1..numRequests) {
                async {
                    sleep(abs(random.nextLong()) % 5000)
                    val body = AsyncReader {
                        it.putAllocatedBytes(10000) { bytes, bytesOffset ->
                            random.nextBytes(bytes, bytesOffset, bytesOffset + 10000)
                        }
                        true
                    }.pipe(createContentLengthPipe(postLength.toLong()))


                    val request =
                        AsyncHttpRequest(URI.create("http://127.0.0.1:${server.localPort}/"), "POST", body = BinaryBody(body, "application/binary"))
                    val data = httpClient.execute(request) { readAllString(it.body!!) }
                    assertEquals(data, "hello world")
                    requestsCompleted++

                    if (requestsCompleted == numRequests)
                        resume.resume(Unit)
                }
            }
        }

        assertEquals(requestsCompleted, numRequests)
    }

    @Test
    fun testDatagram() = networkContextTest {
        val socket1 = createDatagram()
        val socket2 = createDatagram()
        socket1.connect(InetSocketAddress(socket2.localPort))

        // connected write
        val writeBuffer = ByteBufferList()
        writeBuffer.putUtf8String("hello world")
        socket1.write(writeBuffer)

        // connectionless read
        val buffer = ByteBufferList()
        val address = socket2.receivePacket(buffer)
        assertEquals(buffer.readUtf8String(), "hello world")
        assertEquals(address.getPort(), socket1.localPort)

        // connectionless write
        buffer.putUtf8String("ok hi")
        socket2.sendPacket(address, buffer)

        // connected read
        val receiveBuffer = ByteBufferList()
        socket1.read(receiveBuffer)
        assertEquals(receiveBuffer.readUtf8String(), "ok hi")
    }

    @Test
    fun testStops() = networkContextTest {
        stop(true)
        stop(true)
    }
}
