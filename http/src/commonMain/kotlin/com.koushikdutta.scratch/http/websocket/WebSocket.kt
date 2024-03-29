package com.koushikdutta.scratch.http.websocket

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.async.AsyncHandler
import com.koushikdutta.scratch.atomic.AtomicThrowingLock
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.buffers.WritableBuffers
import com.koushikdutta.scratch.codec.base64
import com.koushikdutta.scratch.collections.parseCommaDelimited
import com.koushikdutta.scratch.crypto.sha1
import com.koushikdutta.scratch.extensions.encode
import com.koushikdutta.scratch.extensions.hash
import com.koushikdutta.scratch.http.*
import com.koushikdutta.scratch.http.client.executor.AsyncHttpClientExecutor
import com.koushikdutta.scratch.http.client.executor.AsyncHttpClientSwitchingProtocols
import com.koushikdutta.scratch.parser.parse
import com.koushikdutta.scratch.parser.readString
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
    val isClose: Boolean
        get() = false
}

class WebSocketCloseMessage(val code: Int, val reason: String)

class WebSocket(private val socket: AsyncSocket, reader: AsyncReader = AsyncReader(socket), val protocol: String? = null, server: Boolean = false, val requestHeaders: Headers = Headers(), val responseHeaders: Headers = Headers()): AsyncSocket, AsyncAffinity by socket {
    private val parser = HybiParser(reader, !server)

    val isClosed
        get() = parser.isClosed

    var closeMessage: WebSocketCloseMessage? = null
        internal set

    val isEnded
        get() = parser.isEnded

    val messages = createAsyncIterable<WebSocketMessage> {
        while (true) {
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
                    val reason = message.read.parse().readString()
                    closeMessage = WebSocketCloseMessage(message.closeCode!!, reason)
                    yield(object : WebSocketMessage {
                        override val isClose = true
                    })
                    return@createAsyncIterable
                }
                else if (message.opcode == HybiParser.OP_PING) {
                    val ping = message.read.parse().readString()
                    yield(object : WebSocketMessage {
                        override val isText = true
                        override val text = ping
                        override val isPing = true
                    })
                    break
                }
                else if (message.opcode == HybiParser.OP_PONG) {
                    val pong = message.read.parse().readString()
                    yield(object : WebSocketMessage {
                        override val isText = true
                        override val text = pong
                        override val isPong = true
                    })
                    break
                }

                message.read.siphon(payload)

                if (!message.final)
                    continue

                if (opcode == HybiParser.OP_TEXT) {
                    yield(object : WebSocketMessage {
                        override val isText = true
                        override val text = payload.readUtf8String()
                        override val isData = true
                        override fun toString() = text
                    })
                }
                else {
                    yield(object : WebSocketMessage {
                        override val isText = false
                        override val binary = payload
                        override val isData = true
                    })
                }
                break
            }
        }
    }

    fun send(text: String) = writeHandler.post {
        socket.drain(parser.frame(text))
    }

    fun send(binary: ByteBufferList) = writeHandler.post {
        socket.drain(parser.frame(binary))
    }

    fun ping(text: String) = writeHandler.post {
        socket.drain(parser.pingFrame(text))
    }

    fun pong(text: String) = writeHandler.post {
        socket.drain(parser.pongFrame(text))
    }

    override suspend fun read(buffer: WritableBuffers): Boolean {
        while (!isEnded) {
            val message = messages.iterator().next()

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
            else if (message.isClose) {
                continue
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
            socket.drain(frame)
        }
    }

    private val writeHandler = AsyncHandler(this)
    private val writeLock = AtomicThrowingLock {
        AsyncDoubleWriteException()
    }

    override suspend fun write(buffer: ReadableBuffers) = writeLock {
        writeHandler.run {
            // todo: fragment the packet if its too big?
            val frame = parser.frame(buffer)
            socket.drain(frame)
        }
    }
}

private fun addWebsocketHeaders(headers: Headers, vararg protocols: String) {
    val key = Random.nextBytes(16).encode().base64()
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

suspend fun AsyncHttpClientExecutor.connectWebSocket(uri: String, vararg protocols: String): WebSocket {
    return connectWebSocket(Methods.GET(uri), *protocols)
}

suspend fun AsyncHttpClientExecutor.connectWebSocket(request: AsyncHttpRequest, vararg protocols: String): WebSocket {
    val headers = request.headers
    if (protocols.isNotEmpty())
        headers["Sec-WebSocket-Protocol"] = protocols.joinToString(",")
    addWebsocketHeaders(headers)

    try {
        val response = invoke(request)
        response.close()
        throw IOException("WebSocket connection failed: ${response.code}: ${response.message}")
    }
    catch (switching: AsyncHttpClientSwitchingProtocols) {
        val responseHeaders = switching.responseHeaders

        if (!"websocket".equals(responseHeaders["Upgrade"], true))
            throw WebSocketException("Expected Upgrade: WebSocket header, received: ${responseHeaders["Upgrade"]}")

        val sha1 = responseHeaders["Sec-WebSocket-Accept"] ?: throw WebSocketException("Missing header Sec-WebSocket-Accept")
        val key = headers["Sec-WebSocket-Key"] ?: throw WebSocketException("Missing header Sec-WebSocket-Key")
        val concat = key + MAGIC
        val expected = concat.encodeToByteArray().hash().sha1().encode().base64()
        if (!sha1.equals(expected, true))
            throw WebSocketException("Sha1 mismatch $expected vs $sha1")

        val protocol = responseHeaders["Sec-WebSocket-Protocol"]

        return WebSocket(switching.socket, protocol = protocol, requestHeaders = headers, responseHeaders = responseHeaders)
    }
}

class WebSocketServerSocket: AsyncServerSocket<WebSocket>, AsyncAffinity by AsyncAffinity.NO_AFFINITY {
    internal val queue = AsyncQueue<WebSocket>()
    override fun accept(): AsyncIterable<out WebSocket> {
        return queue
    }

    override suspend fun close() {
        queue.end()
    }

    override suspend fun close(throwable: Throwable) {
        queue.end(throwable)
    }
}

open class WebSocketUpgradeException(message: String): Exception(message)
class WebSocketUpgradeProtocolException(message: String): WebSocketUpgradeException(message)

fun AsyncHttpRequest.checkWebsocketUpgrade(vararg subprotocols: String, block: suspend (webSocket: WebSocket) -> Unit): AsyncHttpResponse? {
    val requestHeaders = headers
    val hasConnectionUpgrade = parseCommaDelimited(requestHeaders["Connection"], String::toLowerCase).containsKey("upgrade")
    val protocols = parseCommaDelimited(requestHeaders["Sec-WebSocket-Protocol"])
    if (!hasConnectionUpgrade)
        return null
    if (!"WebSocket".equals(requestHeaders["Upgrade"], true))
        return null
    if (method.toUpperCase() != Methods.GET.toString())
        throw WebSocketUpgradeException("Expected GET method for WebSocket Upgrade")

    val key = requestHeaders["Sec-WebSocket-Key"]
    if (key == null)
        throw WebSocketUpgradeException("Missing header Sec-WebSocket-Key")

    val headers = Headers()

    // according to spec, the server does not have to agree on a subprotocol, and thus
    // not specify any. the client may choose to disconnect.
    var protocol: String? = null
    if (!subprotocols.isEmpty()) {
        for (subprotocol in protocols.keys) {
            if (protocols.containsKey(subprotocol)) {
                protocol = subprotocol
                headers["Sec-WebSocket-Protocol"] = subprotocol
                break
            }
        }
        if (protocol == null)
            throw WebSocketUpgradeProtocolException("no subprotocols in common ${requestHeaders["Sec-WebSocket-Protocol"]}")
    }

    val concat = key + MAGIC
    val expected = concat.encodeToByteArray().hash().sha1().encode().base64()

    headers["Connection"] = "Upgrade"
    headers["Upgrade"] = "WebSocket"
    headers["Sec-WebSocket-Accept"] = expected

    return AsyncHttpResponse.SWITCHING_PROTOCOLS(headers) {
        block(WebSocket(it, protocol = protocol, server = true, requestHeaders = requestHeaders, responseHeaders = headers))
    }
}