package com.koushikdutta.scratch.http.websocket

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.async.AsyncHandler
import com.koushikdutta.scratch.atomic.AtomicThrowingLock
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.buffers.WritableBuffers
import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.GET
import com.koushikdutta.scratch.http.Headers
import com.koushikdutta.scratch.http.client.AsyncHttpClient
import com.koushikdutta.scratch.http.client.AsyncHttpSwitchingProtocols
import com.koushikdutta.scratch.parser.readAllString
import com.koushikdutta.scratch.uri.URI
import kotlin.random.Random

interface WebSocketMessage {
    val binary: ReadableBuffers
        get() {
            throw IllegalStateException("Message is not a binary message")
        }
    val text: String
        get() {
            throw IllegalStateException("Message is not a binary message")
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

class WebSocket(private val socket: AsyncSocket, reader: AsyncReader, server: Boolean = false): AsyncSocket, AsyncAffinity by socket {
    private val parser = HybiParser(reader, server)

    val isClosed
        get() = parser.isClosed

    val isEnded
        get() = parser.isEnded

    val payload = ByteBufferList()
    suspend fun readMessage(): WebSocketMessage? {
        if (isEnded)
            return null

        payload.free()
        while (true) {
            val message = parser.parse()
            if (message.opcode == HybiParser.OP_CLOSE)
                return null
            if (message.opcode == HybiParser.OP_PING) {
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
                if (message.opcode == HybiParser.OP_TEXT) {
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

    fun ping(text: String) {
        writeHandler.post {
            val frame = parser.pingFrame(text)
            socket::write.drain(frame)
        }
    }

    fun pong(text: String) {
        writeHandler.post {
            val frame = parser.pongFrame(text)
            socket::write.drain(frame)
        }
    }

    override suspend fun read(buffer: WritableBuffers): Boolean {
        while (!isEnded) {
            val message = readMessage()
            if (message == null)
                return true

            if (message.isPing) {
                ping(message.text)
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

suspend fun AsyncHttpClient.connectWebSocket(uri: String, headers: Headers = Headers(), vararg protocols: String): WebSocket {
    val request = AsyncHttpRequest.GET(uri, headers)

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

    try {
        val response = execute(request)
        response.close()
        throw IOException("WebSocket connection failed.")
    }
    catch (switching: AsyncHttpSwitchingProtocols) {
        return WebSocket(switching.socket, switching.socketReader)
    }
}