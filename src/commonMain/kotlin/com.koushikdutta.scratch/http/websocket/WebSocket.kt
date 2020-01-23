package com.koushikdutta.scratch.http.websocket

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.async.AsyncHandler
import com.koushikdutta.scratch.atomic.AtomicThrowingLock
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.buffers.WritableBuffers
import com.koushikdutta.scratch.crypto.sha1
import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.GET
import com.koushikdutta.scratch.http.Headers
import com.koushikdutta.scratch.http.client.AsyncHttpClient
import com.koushikdutta.scratch.http.client.AsyncHttpClientSwitchingProtocols
import com.koushikdutta.scratch.parser.readAllString
import kotlin.random.Random


interface WebSocketMessage {
    val binary: ReadableBuffers
        get() {
            throw IllegalStateException("Message is not a binary message")
        }
    val text: String
        get() {
            throw IllegalStateException("Message is not a text message")
        }

    val isData: Boolean
        get() = false
    val isText: Boolean
        get() = false
    val isBinary: Boolean
        get() = false
    val isPing: Boolean
        get() = false
    val isPong: Boolean
        get() = false
}

class WebSocketCloseMessage(val code: Int, val reason: String)

class WebSocket(private val socket: AsyncSocket, reader: AsyncReader, val protocol: String? = null, server: Boolean = false): AsyncSocket, AsyncAffinity by socket {
    private val parser = HybiParser(reader, server)

    val isClosed
        get() = parser.isClosed

    var closeMessage: WebSocketCloseMessage? = null
        internal set

    val isEnded
        get() = parser.isEnded

    suspend fun readMessage(): WebSocketMessage? {
        if (isEnded)
            return null

        val payload = ByteBufferList()
        var opcode: Int? = null
        while (true) {
            val message = try {
                parser.parse()
            }
            catch (throwable: Throwable) {
                socket.close()
                throw throwable
            }

            // the parser will validate the protocol on a per frame basis,
            // but across multiple frames, when continuation frames are in use.
            if (opcode == null) {
                if (message.opcode == HybiParser.OP_CONTINUATION)
                    throw HybiProtocolError("did not receive data frame prior to continuation frame")
                opcode = message.opcode
            }
            else if (opcode != HybiParser.OP_CONTINUATION) {
                throw HybiProtocolError("expected continuation frame")
            }

            if (message.opcode == HybiParser.OP_CLOSE) {
                val reason = readAllString(message.read)
                closeMessage = WebSocketCloseMessage(message.closeCode, reason)
                return null
            }
            else if (message.opcode == HybiParser.OP_PING) {
                val ping = readAllString(message.read)
                return object : WebSocketMessage {
                    override val isText = true
                    override val text = ping
                    override val isPing = true
                }
            }
            else if (message.opcode == HybiParser.OP_PONG) {
                val pong = readAllString(message.read)
                return object : WebSocketMessage {
                    override val isText = true
                    override val text = pong
                    override val isPong = true
                }
            }

            message.read.drain(payload)

            if (message.final) {
                if (opcode == HybiParser.OP_TEXT) {
                    return object : WebSocketMessage {
                        override val isText = true
                        override val text = payload.readUtf8String()
                        override val isData = true
                    }
                }
                else {
                    return object : WebSocketMessage {
                        override val isText = false
                        override val binary = payload
                        override val isData = true
                    }
                }
            }
        }
    }

    fun send(text: String) = writeHandler.post {
        socket::write.drain(parser.frame(text))
    }

    fun send(binary: ByteBufferList) = writeHandler.post {
        socket::write.drain(parser.frame(binary))
    }

    fun ping(text: String) = writeHandler.post {
        socket::write.drain(parser.pingFrame(text))
    }

    fun pong(text: String) = writeHandler.post {
        socket::write.drain(parser.pongFrame(text))
    }

    override suspend fun read(buffer: WritableBuffers): Boolean {
        while (!isEnded) {
            val message = readMessage()
            if (message == null)
                return true

            if (message.isPing) {
                pong(message.text)
                continue
            }
            else if (message.isPong) {
                continue
            }
            else if (message.isText) {
                throw IOException("WebSocket expected binary data, but received text")
            }

            message.binary.read(buffer)
            return true
        }

        return false
    }

    override suspend fun close() {
        close(0, "")
    }

    fun close(code: Int, reason: String) {
        writeHandler.post {
            val frame = parser.closeFrame(code, reason)
            socket::write.drain(frame)
        }
    }

    private val writeHandler = AsyncHandler(this)
    private val writeLock = AtomicThrowingLock {
        IOException("write already in progress")
    }

    override suspend fun write(buffer: ReadableBuffers) = writeLock {
        writeHandler.run {
            // todo: fragment the packet if its too big?
            val frame = parser.frame(buffer)
            socket::write.drain(frame)
        }
    }
}

private fun addWebsocketHeaders(headers: Headers, vararg protocols: String) {
    val key = Random.nextBytes(16).encodeBase64ToString()
    headers["Sec-WebSocket-Version"] = "13";
    headers["Sec-WebSocket-Key"] = key;
    headers["Connection"] = "Upgrade";
    headers["Upgrade"] = "websocket";
    headers["Pragma"] = "no-cache";
    headers["Cache-Control"] = "no-cache";

    for (protocol in protocols) {
        headers.add("Sec-WebSocket-Protocol", protocol)
    }
}

class WebSocketException(message: String): IOException(message)
private const val MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

suspend fun AsyncHttpClient.connectWebSocket(uri: String, socket: AsyncSocket? = null, reader: AsyncReader? = null, headers: Headers = Headers(), vararg protocols: String): WebSocket {
    val request = AsyncHttpRequest.GET(uri, headers)

    addWebsocketHeaders(headers)

    try {
        val response = execute(request, socket, reader)
        response.close()
        throw IOException("WebSocket connection failed.")
    }
    catch (switching: AsyncHttpClientSwitchingProtocols) {
        val responseHeaders = switching.responseHeaders

        if (!"websocket".equals(responseHeaders["Upgrade"], true))
            throw WebSocketException("Expected Upgrade: WebSocket header, received: ${responseHeaders["Upgrade"]}")

        val sha1 = responseHeaders["Sec-WebSocket-Accept"] ?: throw WebSocketException("Missing header Sec-WebSocket-Accept")
        val key = headers["Sec-WebSocket-Key"] ?: throw WebSocketException("Missing header Sec-WebSocket-Key")
        val concat = key + MAGIC
        val expected = concat.encodeToByteArray().sha1().encodeBase64ToString()
        if (!sha1.equals(expected, true))
            throw WebSocketException("Sha1 mismatch $expected vs $sha1")

        val protocol = responseHeaders["Sec-WebSocket-Protocol"]

        return WebSocket(switching.socket, switching.socketReader, protocol)
    }
}
