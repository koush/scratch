package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.createByteBufferList
import com.koushikdutta.scratch.http.server.AsyncHttpRouter
import com.koushikdutta.scratch.http.server.AsyncHttpServer
import com.koushikdutta.scratch.http.server.webSocket
import com.koushikdutta.scratch.http.websocket.HybiParser
import com.koushikdutta.scratch.http.websocket.WebSocket
import com.koushikdutta.scratch.http.websocket.connectWebSocket
import com.koushikdutta.scratch.parser.*
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class WebsocketTests {
    fun testHybiParser(length: Int) {
        val pipe = createAsyncPipeSocketPair()
        val clientSocket = pipe.first
        val serverSocket = pipe.second
        val client = HybiParser(AsyncReader(clientSocket), true)
        val server = HybiParser(AsyncReader(serverSocket), false)

        val testData = Random.Default.nextBytes(length)
        val digest = CrappyDigest.getInstance().update(testData).digest().createByteBufferList().readLong()

        var done = 0
        async {
            serverSocket.drain(server.frame(testData.createByteBufferList()))
            val message = server.parse()
            val check = CrappyDigest.getInstance().update(message.read.parse().readBytes()).digest().createByteBufferList().readLong()
            assertEquals(check, digest)
            done++
        }

        async {
            val message = client.parse()
            val testDataCopy = message.read.parse().readBytes()
            val check = CrappyDigest.getInstance().update(testDataCopy).digest().createByteBufferList().readLong()
            assertEquals(check, digest)
            clientSocket.drain(client.frame(testDataCopy.createByteBufferList()))
            done++
        }
        assertEquals(done, 2)
    }

    @Test
    fun testHybiParserByteLength() {
        testHybiParser(125)
    }

    @Test
    fun testHybiParserShortLength() {
        testHybiParser(65536)
    }

    @Test
    fun testHybiParserIntLength() {
        testHybiParser(1024 * 1024)
    }

    @Test
    fun testHybiParserPingPong() {
        val pipe = createAsyncPipeSocketPair()
        val clientSocket = pipe.first
        val serverSocket = pipe.second
        val client = HybiParser(AsyncReader(clientSocket), true)
        val server = HybiParser(AsyncReader(serverSocket), false)

        var done = 0
        async {
            serverSocket.drain(server.pingFrame("hello"))
            val message = server.parse()
            assertEquals(HybiParser.OP_PONG, message.opcode)
            val check = message.read.parse().readString()
            assertEquals(check, "hello")
            done++
        }

        async {
            val message = client.parse()
            val check = message.read.parse().readString()
            assertEquals(HybiParser.OP_PING, message.opcode)
            assertEquals(check, "hello")
            clientSocket.drain(client.pongFrame("hello"))
            done++
        }
        assertEquals(done, 2)
    }

    @Test
    fun testHybiParserClose() {
        val pipe = createAsyncPipeSocketPair()
        val clientSocket = pipe.first
        val serverSocket = pipe.second
        val client = HybiParser(AsyncReader(clientSocket), true)
        val server = HybiParser(AsyncReader(serverSocket), false)

        var done = 0
        async {
            serverSocket.drain(server.pingFrame("hello"))
            val message = server.parse()
            assertEquals(HybiParser.OP_PONG, message.opcode)
            val check = message.read.parse().readString()
            assertEquals(check, "hello")
            serverSocket.drain(server.closeFrame(55, "dead"))
            done++
        }

        async {
            val message = client.parse()
            assertEquals(HybiParser.OP_PING, message.opcode)
            assertEquals(message.read.parse().readString(), "hello")
            clientSocket.drain(client.pongFrame("hello"))

            val message2 = client.parse()
            assertEquals(message2.closeCode, 55)
            assertEquals(HybiParser.OP_CLOSE, message2.opcode)
            assertEquals(message2.read.parse().readString(), "dead")
            done++
        }
        assertEquals(done, 2)
    }

    @Test
    fun testWebSocket() {
        val pipe = createAsyncPipeSocketPair()
        val clientSocket = WebSocket(pipe.first)
        val serverSocket = WebSocket(pipe.second, server = true)

        var data: String? = null
        async {
            clientSocket.drain("hello".createByteBufferList())
            clientSocket.drain("world".createByteBufferList())
            clientSocket.close()
        }

        async {
            data = serverSocket.parse().readString()
        }

        assertEquals("helloworld", data)
    }

    @Test
    fun testWebSocketServer() {
        val router = AsyncHttpRouter()
        val httpServer = AsyncHttpServer(router::handle)

        val webSocketServer = router.webSocket("/websocket")
        var data: String? = null
        webSocketServer.acceptAsync {
            data = this.parse().readString()
        }

        val httpClient = httpServer.createFallbackClient()
        async {
            val clientSocket = httpClient.connectWebSocket("/websocket")
            clientSocket.drain("hello".createByteBufferList())
            clientSocket.drain("world".createByteBufferList())
            clientSocket.close()
        }

        assertEquals("helloworld", data)
    }
}
