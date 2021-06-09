package com.koushikdutta.scratch

import com.koushikdutta.scratch.TestUtils.Companion.countBytes
import com.koushikdutta.scratch.TestUtils.Companion.networkContextTest
import com.koushikdutta.scratch.async.async
import com.koushikdutta.scratch.buffers.*
import com.koushikdutta.scratch.event.*
import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.Methods
import com.koushikdutta.scratch.http.StatusCode
import com.koushikdutta.scratch.http.body.BinaryBody
import com.koushikdutta.scratch.http.body.Utf8StringBody
import com.koushikdutta.scratch.http.client.AsyncHttpClient
import com.koushikdutta.scratch.http.client.createContentLengthPipe
import com.koushikdutta.scratch.http.client.execute
import com.koushikdutta.scratch.http.client.executor.*
import com.koushikdutta.scratch.http.client.get
import com.koushikdutta.scratch.http.server.AsyncHttpServer
import com.koushikdutta.scratch.http.websocket.connectWebSocket
import com.koushikdutta.scratch.parser.readAllString
import com.koushikdutta.scratch.uri.URI
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.*

class LoopTests {
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
            0
        }

        networkContext.postDelayed(3000) {
            throw TimeoutException()
        }

        try {
            networkContext.run()
            result.getCompleted()
            fail("exception expected")
        }
        catch (exception: Exception) {
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
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
            0
        }

        networkContext.postDelayed(1000) {
            throw TimeoutException()
        }

        try {
            networkContext.run()
            result.getCompleted()
            fail("exception expected")
        }
        catch (exception: Exception) {
        }
    }


    @OptIn(ExperimentalCoroutinesApi::class)
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
        assertEquals(result.getCompleted(), 42)
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
        val reader = AsyncReader(client)
        assertEquals(reader.readUtf8String(6), "hello!")
    }

    @Test
    fun testServerExpectCrash() = networkContextTest(true) {
        val server = listen()
        val observer = server.acceptAsync {
            throw ExpectedException()
        }
        val client = connect("127.0.0.1", server.localPort)
        try {
            client.write(ByteBufferList().putUtf8String("hello!"))
            val reader = AsyncReader(client)
            reader.readUtf8String(1)
        }
        catch (exception: IOException) {
            // the server error should trigger a client close
            // ignore and verify the server throws itself.
        }
        observer.await()
    }

    @Test
    fun testServerNotCrash() = networkContextTest {
        val server = listen()
        val observer = server.acceptAsync(ignoreErrors = true) {
            throw ExpectedException()
        }
        val client = connect("127.0.0.1", server.localPort)
        try {
            client.write(ByteBufferList().putUtf8String("hello!"))
            val reader = AsyncReader(client)
                reader.readUtf8String(1)
        }
        catch (exception: IOException) {
        }
        server.close()
        observer.await()
    }

    @Test
    fun testServerALot() = networkContextTest {
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

        var count = 0
        (0 until runs).map {
            async {
                val read = connect("127.0.0.1", server.localPort).countBytes()
                count += read
            }
            .asPromise()
        }
        .awaitAll()

        assertEquals(count, 1000000 * runs)
    }

    @Test
    fun testByteBufferAllocations() = networkContextTest {
        val start = ByteBufferList.totalObtained
        val server = listen(0)

        server.acceptAsync {
            TestUtils.createRandomRead(10000000).copy(this)
            close()
        }

        val count  = async {
            connect("127.0.0.1", server.localPort).countBytes()
        }
        .await()

        assertEquals(count, 10000000)
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
        server.acceptAsync {
            // need to accept/close so handles are freed.
        }
        var connected = 0
        // there seems to be a weird issue with opening a ton of file descriptors too quickly.
        // the file limit is not hit, but connects will fail with "Connection reset by peer"
        // even though this code is leaving the connection open.
        // sticking a sleep in there seems to fix it.
        val connectCount = 5000
        val random = Random.Default
        val promises = mutableListOf<Promise<Unit>>()
        for (i in 0 until connectCount) {
            // this test is flaky. connection resets if the system can't handle it.
            val promise = async {
                while (true) {
                    try {
                        sleep(abs(random.nextLong()) % 5000)
                        val socket = connect("127.0.0.1", server.localPort)
                        sleep(10)
                        socket.close()
                        connected++
                        break
                    }
                    catch (throwable: Throwable) {
                        continue
                    }
                }
            }
            .asPromise()
            promises.add(promise)
        }
        promises.awaitAll()
        assertEquals(connectCount, connected)
    }

    @Test
    fun testHttpALot() = networkContextTest {
        val postLength = 100000
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
            StatusCode.OK(body = Utf8StringBody("hello world"))
        }

        httpServer.listenAsync(server)

        val numRequests = 1000
        var requestsCompleted = 0
        val httpClient = AsyncHttpClient(this)
        val random = Random.Default
        val promises = mutableListOf<Promise<Unit>>()

        for (i in 1..numRequests) {
            val promise = async {
                // due to some platform specific goofiness, this may cause connection resets. so keep trying until success.
                while (true) {
                    try {
                        sleep(abs(random.nextLong()) % 5000)
                        val body = AsyncReader(AsyncRead {
                            it.putAllocatedBytes(10000) { bytes, bytesOffset ->
                                random.nextBytes(bytes, bytesOffset, bytesOffset + 10000)
                            }
                            true
                        }).pipe(createContentLengthPipe(postLength.toLong()))

                        val request =
                                AsyncHttpRequest(URI("http://127.0.0.1:${server.localPort}/"), "POST", body = BinaryBody(body, "application/binary"))
                        val data = httpClient.execute(request) { readAllString(it.body!!) }
                        assertEquals(data, "hello world")
                        requestsCompleted++
                        break
                    }
                    catch (throwable: Throwable) {
                        continue
                    }
                }
                Unit
            }
            .asPromise()

            promises.add(promise)
        }

        promises.awaitAll()

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

    @Test
    fun testWebSocket() = networkContextTest {
        val httpClient = AsyncHttpClient(this)
        val websocket = httpClient.connectWebSocket("wss://echo.websocket.org")

        websocket.ping("ping!")
        assertEquals("ping!", websocket.readMessage().text)

        websocket.drain("hello".createByteBufferList())
        websocket.drain("world".createByteBufferList())
        websocket.close()
        val data = readAllString(websocket)
        assertEquals("helloworld", data)
    }

    @Test
    fun testRunSleep() = AsyncEventLoop().run {
        sleep(10)
    }

    @Test
    fun testMultipleIPFailureFallback() = AsyncEventLoop().run() {
        val cwm = getAllByName("clockworkmod.com")
        val loop = this
        val httpClient = AsyncHttpClient(loop)
        httpClient.schemeExecutor.useHttpExecutor(loop) {
            createAsyncIterable {
                val port = it.getPortOrDefault(80)

                try {
                    yield(connect("0.0.0.0", port))
                }
                catch (ignored: Throwable) {

                }

                for (address in cwm) {
                    yield(connect(InetSocketAddress(address, port)))
                }
            }
        }

        try {
            httpClient.get("http://clockworkmod.com") {
                readAllString(it.body!!)
            }
            fail("expected failure")
        }
        catch (throwable: Throwable) {
        }

        httpClient.schemeExecutor.useHttpExecutor(loop) {
            val port = it.getPortOrDefault(80)

            createAsyncIterable {
                for (address in cwm) {
                    yield(connect(InetSocketAddress(address, port)))
                }
            }
        }

        assertNotNull(httpClient.get("http://clockworkmod.com") {
            readAllString(it.body!!)
        })
        Unit
    }

    @Test
    fun testCreateConnect() = networkContextTest {
        val server = listen()
        val client = createSocket()

        val promise = Promise {
            client.connect("127.0.0.1", server.localPort)
        }

        promise.await()
    }

    @Test
    fun testCreateConnectClose() = networkContextTest {
        val server = listen()
        val client = createSocket()

        val promise = Promise {
            client.connect("127.0.0.1", server.localPort)
        }

        client.close()
        try {
            promise.await()
            fail("connection failure expected")
        }
        catch (throwable: Throwable) {
        }
    }

    @Test
    fun testConnectCancel() = networkContextTest {
        val server = listen()

        var failed = false
        val promise = Promise {
            try {
                connect("127.0.0.1", server.localPort)
                fail("cancellation expected")
            }
            catch (throwable: CancellationException) {
                failed = true
            }
        }

        // post to trigger the connect
        post()
        // cancel will end the coroutine
        promise.cancel()
        try {
            // will throw due to cancel
            promise.await()
            fail("connection failure expected")
        }
        catch (throwable: Throwable) {
            // post to trigger close/cancellation
            post()
        }

        assertTrue(failed)
    }

    @Test
    fun testSleepCancel() = networkContextTest {
        var failed = false
        val promise = Promise {
            try {
                sleep(1000000)
                Promise.ensureActive()
                fail("cancellation expected")
            }
            catch (throwable: CancellationException) {
                failed = true
            }
        }

        post()
        promise.cancel()

        try {
            promise.await()
            fail("sleep failure expected")
        }
        catch (throwable: Throwable) {
        }

        post()
        assertTrue(failed)
    }

    @Test
    fun testDispatcher() = networkContextTest {
        val job = async {
            assertTrue(isAffinityThread)
            await()
            assertTrue(isAffinityThread)
        }

        job.await()

        // requires confined dispatcher.
        val job2 = GlobalScope.async {
            assertFalse(isAffinityThread)
            await()
            assertTrue(isAffinityThread)
        }

        job2.await()

        // requires confined dispatcher.
        val job3 = GlobalScope.async {
            assertFalse(isAffinityThread)
        }
        job3.await()
    }

    @Test
    fun testPromiseMultipleCallbacks() = networkContextTest{
        val promise = Promise {
            post()
            Unit
        }

        var count = 0
        promise.then {
            count++
        }

        promise.then {
            count++
        }

        promise.await()
        post()
        assertEquals(count, 2)
    }

    @Test
    fun testHttpProxy() = networkContextTest {
        val request = Methods.GET("https://www.clockworkmod.com")
        request.setProxy("192.168.2.7", 8888)
        val httpClient = AsyncHttpClient(this)
        val ret = readAllString(httpClient(request).body!!)
        println(ret)
    }

    @Test
    fun testTimeout() = networkContextTest(true) {
        val deferred = CompletableDeferred<Unit>()

        try {
            timeout(10) {
                deferred.await()
            }
        }
        catch (throwable: CancellationException) {
            throw ExpectedException()
        }
    }

    @Test
    fun testTimeout2() = networkContextTest {
        val deferred = CompletableDeferred<Unit>(Unit)

        try {
            timeout(10) {
                deferred.await()
            }
        }
        catch (throwable: CancellationException) {
            throw ExpectedException()
        }
    }

    @Test
    fun testBigBuffer() = networkContextTest {
        val server = listen()
        server.acceptAsync {
            val buffer = allocateByteBuffer(10000000)
            drain(ByteBufferList(buffer))
            close()
        }

        val s2 = connect(InetSocketAddress(getLoopbackAddress(), server.localPort))
        val len = s2.countBytes()
        assertEquals(10000000, len)
    }
}
