package com.koushikdutta.scratch.http.http2

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.async.AsyncHandler
import com.koushikdutta.scratch.async.launch
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.buffers.WritableBuffers
import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.AsyncHttpResponse
import com.koushikdutta.scratch.http.Headers
import com.koushikdutta.scratch.http.StatusCode
import com.koushikdutta.scratch.http.client.AsyncHttpExecutor
import com.koushikdutta.scratch.http.http2.okhttp.*
import com.koushikdutta.scratch.http.http2.okhttp.Settings.Companion.DEFAULT_INITIAL_WINDOW_SIZE

internal fun List<Header>.toHeaders(): Headers {
    val headers = Headers()
    for (header in this) {
        headers.add(header.name.string, header.value.string)
    }
    return headers
}

internal fun Headers.toHeaders(): List<Header> {
    val headers = mutableListOf<Header>()
    for (header in this) {
        headers.add(Header(header.name, header.value))
    }
    return headers
}

class Http2ResetException(val errorCode: ErrorCode, message: String): Exception(message)

class Http2Socket internal constructor(val connection: Http2Connection, val streamId: Int, val pushPromise: Boolean = false) : AsyncSocket, AsyncAffinity by connection.socket {
    internal val incomingHeaders = AsyncQueue<Headers>()
    suspend fun readHeaders() = incomingHeaders.iterator().next()
    private var writeBytesAvailable: Long = connection.peerSettings.initialWindowSize.toLong()
    internal val input = NonBlockingWritePipe {
        acknowledgeData()
    }
    internal val output = BlockingWritePipe {
        // break the data into write frames, until it's all written.
        while (it.hasRemaining() && writeBytesAvailable > 0) {
            // perform each write frame on the connection handler, not the whole operation, to allow write interleaving
            // from other streams.
            connection.handler.run {
                // determine the maximum amount that can be written given the stream and connection windows.
                val chunkMin = minOf(it.remaining().toLong(), writeBytesAvailable)
                val connectionMin = minOf(connection.writer.maxDataLength().toLong(), connection.writeBytesAvailable)
                val toWrite = minOf(chunkMin, connectionMin)

                // update the windows
                writeBytesAvailable -= toWrite
                connection.writeBytesAvailable -= toWrite

                connection.writer.data(false, streamId, it, toWrite.toInt())
                // wait for write to finish on the connection.
                connection.flushData()
            }
        }
    }

    override suspend fun read(buffer: WritableBuffers) = input.read(buffer)

    internal fun addBytesToWriteWindow(delta: Long) {
        writeBytesAvailable += delta
        if (writeBytesAvailable > 0)
            output.writable()
    }

    suspend fun writeHeaders(headers: Headers, outFinished: Boolean) = connection.handler.run {
        if (outFinished)
            output.close()
        connection.writer.headers(outFinished, streamId, headers.toHeaders())
        connection.flush()
    }

    override suspend fun write(buffer: ReadableBuffers) {
        output.write(buffer)
    }

    internal fun endInputInternal(throwable: Throwable?): Boolean {
        return if (throwable != null) {
            incomingHeaders.end(throwable)
            input.end(throwable)
        }
        else {
            incomingHeaders.end()
            input.end()
        }
    }

    // stops reading the input, tells peer to rst
    internal suspend fun endInput(errorCode: ErrorCode, throwable: Throwable?): Boolean {
        // input may have already ended.
        if (!endInputInternal(throwable))
            return false

        connection.handler.run {
            connection.writer.rstStream(streamId, errorCode)
            connection.flushIgnoringTransportErrors()
        }
        return true
    }

    suspend fun endInput(throwable: Throwable?) = endInput(ErrorCode.CANCEL, throwable)

    suspend fun closeOutput(throwable: Throwable? = null): Boolean {
        val result = if (throwable != null)
            output.close(throwable)
        else
            output.close()

        if (!result)
            return false

        connection.handler.run {
            connection.writer.data(true, streamId, null, 0)
            connection.flushIgnoringTransportErrors()
        }
        return true
    }

    // called by user code
    override suspend fun close() {
        await()

        endInput(ErrorCode.CANCEL, null)
        closeOutput()
    }

    private fun acknowledgeData() {
        if (unacknowledgedBytes == 0L)
            throw AssertionError("flush called with nothing to flush")
        val delta = unacknowledgedBytes
        unacknowledgedBytes = 0
        connection.writeWindowUpdateLater(streamId, delta)
    }

    private var unacknowledgedBytes = 0L
    internal fun data(inFinished: Boolean, source: BufferedSource) {
        unacknowledgedBytes += source.remaining()
        if (input.write(source) && (unacknowledgedBytes > connection.localSettings.initialWindowSize / 2)) {
            acknowledgeData()
        }
        if (inFinished) {
            incomingHeaders.end()
            input.end()
        }
    }
}

class Http2Connection private constructor(val socket: AsyncSocket, val client: Boolean, socketReader: AsyncReader = AsyncReader(socket::read)): AsyncResource by socket, AsyncServerSocket<Http2Socket> {
    private val pushPromises = mutableMapOf<String, Http2Socket>()
    private val incomingSockets = AsyncQueue<Http2Socket>()
    internal val handler = AsyncHandler(socket)
    internal var lastGoodStreamId: Int = 0
    private var nextStreamId = if (client) 3 else 2
    internal val streams = mutableMapOf<Int, Http2Socket>()
    internal val localSettings = Settings().apply {
        // Flow control was designed more for servers, or proxies than edge clients. If we are a client,
        // set the flow control window to 16MiB.  This avoids thrashing window updates every 64KiB, yet
        // small enough to avoid blowing up the heap.
        if (client) {
            set(Settings.INITIAL_WINDOW_SIZE, DEFAULT_CLIENT_WINDOW_SIZE)
        }
    }
    internal var peerSettings = DEFAULT_SETTINGS
    private var unacknowledgedBytes = 0L
    internal var writeBytesAvailable: Long = peerSettings.initialWindowSize.toLong()
        internal set

    internal val pinger = Yielder()
    internal var awaitingPong = false

    // okhttp uses a blocking sink. after all write operations, gotta flush this sink by actually
    // writing to the socket.
    private val sink = ByteBufferList()
    internal val writer = Http2Writer(sink, client)
    private val output = BlockingWritePipe {
        // if the write would consume the window, block until
        // the window size is increased again. this prevents
        // spin loops of zero length writes.
        if (writeBytesAvailable <= 0)
            return@BlockingWritePipe
        socket::write.drain(it)
    }
    internal val reader = Http2Reader(socket, socketReader)
    private val readerHandler = object : Http2Reader.Handler {
        override fun data(inFinished: Boolean, streamId: Int, source: BufferedSource, length: Int) {
            val stream = streams[streamId]
            if (stream == null) {
                rstStream(streamId, ErrorCode.PROTOCOL_ERROR)
                return
            }
            // length isn't used here as the source is simply a buffer of length
            stream.data(inFinished, source)
            updateConnectionFlowControl(length.toLong())
        }

        override fun headers(inFinished: Boolean, streamId: Int, associatedStreamId: Int, headerBlock: List<Header>) {
            if (streams.contains(streamId)) {
                val stream = streams[streamId]!!
                stream.incomingHeaders.add(headerBlock.toHeaders())

                if (inFinished)
                    stream.endInputInternal(null)
                return
            }

            if ((client && streamId % 2 == 1) || (!client && streamId % 2 == 0)) {
                // the peer may be responding to a socket that has already been closed.
                // race condition. Can safely ignore that.
                if (streamId > lastGoodStreamId) {
                    handler.post {
                        close(ErrorCode.PROTOCOL_ERROR, ErrorCode.PROTOCOL_ERROR, IOException("unknown stream id"))
                    }
                }
                return
            }

            // incoming request.
            lastGoodStreamId = streamId
            val stream = Http2Socket(this@Http2Connection, streamId)
            streams[streamId] = stream
            if (inFinished)
                stream.input.end()

            stream.incomingHeaders.add(headerBlock.toHeaders())
            incomingSockets.add(stream)
        }

        override fun rstStream(streamId: Int, errorCode: ErrorCode) {
            val stream = streams.remove(streamId)
            val reset = Http2ResetException(errorCode, "http2 stream reset: $errorCode")
            stream?.input?.end(reset)
            stream?.output?.close(reset)
        }

        override fun settings(clearPrevious: Boolean, settings: Settings) {
            val previousPeerSettings = peerSettings
            val newPeerSettings = if (clearPrevious) {
                settings
            } else {
                Settings().apply {
                    merge(previousPeerSettings)
                    merge(settings)
                }
            }

            val peerInitialWindowSize = newPeerSettings.initialWindowSize.toLong()
            val delta = peerInitialWindowSize - previousPeerSettings.initialWindowSize.toLong()

            peerSettings = newPeerSettings

            handler.post {
                writer.applyAndAckSettings(newPeerSettings)
                flush()
            }

            if (delta != 0L) {
                for (stream in streams.values) {
                    stream.addBytesToWriteWindow(delta)
                }
            }
        }

        override fun ackSettings() {
        }

        override fun ping(ack: Boolean, payload1: Int, payload2: Int) {
            if (ack) {
                awaitingPong = false
                pinger.resume()
            } else {
                handler.post {
                    writePing(true, payload1, payload2)
                }
            }
        }

        override fun goAway(lastGoodStreamId: Int, errorCode: ErrorCode, debugData: ByteString) {
            handler.post {
                close(IOException("http2 goAway received, shutting down"))
            }
        }

        override fun windowUpdate(streamId: Int, windowSizeIncrement: Long) {
            if (streamId == 0) {
                addBytesToWriteWindow(windowSizeIncrement)
            }
            else {
                streams[streamId]?.addBytesToWriteWindow(windowSizeIncrement)
            }
        }

        override fun priority(streamId: Int, streamDependency: Int, weight: Int, exclusive: Boolean) {
        }

        override fun pushPromise(inFinished: Boolean, streamId: Int, promisedStreamId: Int, requestHeaders: List<Header>) {
            lastGoodStreamId = promisedStreamId
            val stream = Http2Socket(this@Http2Connection, promisedStreamId, true)
            streams[promisedStreamId] = stream
            if (inFinished)
                stream.input.end()

            val headers = requestHeaders.toHeaders()

            val pushPromiseKey = headers.getPushPromiseKey()
            if (pushPromiseKey == null)
                println("push promise key could not be computed?")
            else
                pushPromises[pushPromiseKey] = stream
        }

        override fun alternateService(streamId: Int, origin: String, protocol: ByteString, host: String, port: Int, maxAge: Long) {
        }
    }

    private fun addBytesToWriteWindow(delta: Long) {
        writeBytesAvailable += delta
        if (writeBytesAvailable > 0)
            output.writable()
    }

    private fun writePing(
            reply: Boolean,
            payload1: Int,
            payload2: Int
    ) {
        if (!reply) {
            if (awaitingPong) {
                handler.post {
                    close(IOException("missing pong"))
                }
                return
            }
            awaitingPong = true
        }

        handler.post {
            writer.ping(reply, payload1, payload2)
            flush()
        }
    }

    fun ping() {
        writePing(false, 0x4f4b6f6b /* "OKok" */, -0xf607257 /* donut */)
    }

    internal suspend fun close(connectionCode: ErrorCode, streamCode: ErrorCode, throwable: Throwable?) {
        val shutdown = if (throwable != null)
            incomingSockets.end(throwable)
        else
            incomingSockets.end()

        if (!shutdown)
            return

        handler.run {
            writer.goAway(lastGoodStreamId, connectionCode, ByteArray(0))
            flushIgnoringTransportErrors()
        }

        socket.await()

        for (stream in streams.values) {
            stream.closeOutput(throwable)
            stream.endInput(streamCode, throwable)
        }

        // Close the socket to break out the reader thread, which will clean up after itself.
        socket.close()
    }

    companion object {
        private val pushPromiseHeaders = listOf(Header.TARGET_METHOD_UTF8, Header.TARGET_AUTHORITY_UTF8, Header.TARGET_PATH_UTF8, Header.TARGET_SCHEME_UTF8)
        private fun Headers.getPushPromiseKey(): String? {
            var key = ""
            for (header in pushPromiseHeaders) {
                val headerValue = this[header]
                if (headerValue == null)
                    return null
                key += headerValue
            }
            return key
        }

        suspend fun upgradeHttp2Connection(socket: AsyncSocket, mode: Http2ConnectionMode, socketReader: AsyncReader = AsyncReader {socket.read(it)}): Http2Connection {
            val client = mode == Http2ConnectionMode.Client
            val readConnectionPreface = mode == Http2ConnectionMode.Server
            val connection = Http2Connection(socket, client, socketReader)
            connection.run {
                handler.post {
                    writer.connectionPreface()
                    writer.settings(localSettings)
                    val windowSize = localSettings.initialWindowSize
                    if (windowSize != DEFAULT_INITIAL_WINDOW_SIZE) {
                        writer.windowUpdate(0, (windowSize - DEFAULT_INITIAL_WINDOW_SIZE).toLong())
                    }
                    flush()
                }
            }

            if (readConnectionPreface && !client)
                connection.reader.readConnectionPreface()

            if (connection.reader.nextFrame(connection.readerHandler) != Http2.TYPE_SETTINGS)
                throw IOException("Expected http2 settings frame")

            while (true) {
                val type = connection.reader.nextFrame(connection.readerHandler)
                if (type == Http2.TYPE_WINDOW_UPDATE)
                    continue
                else if (type == Http2.TYPE_SETTINGS)
                    break
                throw IOException("Expected http2 window update or http2 settings ack")
            }
            connection.processMessages()
            return connection
        }

        const val DEFAULT_CLIENT_WINDOW_SIZE = 16 * 1024 * 1024
        val DEFAULT_SETTINGS = Settings().apply {
            set(Settings.INITIAL_WINDOW_SIZE, DEFAULT_INITIAL_WINDOW_SIZE)
            set(Settings.MAX_FRAME_SIZE, Http2.INITIAL_MAX_FRAME_SIZE)
        }
    }

    private fun processMessages() = socket.launch {
        try {
            while (true) {
                reader.nextFrame(readerHandler)
            }
        }
        catch (t: Throwable) {
            close(t)
            return@launch
        }
    }

    internal fun writeWindowUpdateLater(streamId: Int, unacknowledgedBytes: Long) = handler.post {
        if (unacknowledgedBytes == 0L)
            return@post
        writer.windowUpdate(streamId, unacknowledgedBytes)
        flush()
    }

    internal suspend fun flush() {
        try {
            socket::write.drain(sink)
        }
        catch (throwable: Throwable) {
            // close will also attempt to flush a connection shutdown, but it only
            // attempts once
            close(throwable)
            throw throwable
        }
    }

    internal suspend fun flushIgnoringTransportErrors() {
        try {
            flush()
        }
        catch (ignored: Throwable) {
            sink.free()
        }
    }

    internal suspend fun flushData() {
        output::write.drain(sink)
    }

    private fun acknowledgeData() {
        if (unacknowledgedBytes == 0L)
            throw AssertionError("flush called with nothing to flush")
        val delta = unacknowledgedBytes
        unacknowledgedBytes = 0
        writeWindowUpdateLater(0, delta)
    }

    internal fun updateConnectionFlowControl(length: Long) {
        unacknowledgedBytes += length
        if (unacknowledgedBytes > localSettings.initialWindowSize / 2) {
            acknowledgeData()
        }
    }

    suspend fun connect(headers: Headers, outFinished: Boolean): Http2Socket {
        await()

        val pushPromiseKey = headers.getPushPromiseKey()
        if (pushPromiseKey != null) {
            val found = pushPromises.remove(pushPromiseKey)
            if (found != null)
                return found
        }

        val streamId = nextStreamId
        nextStreamId += 2

        val stream = Http2Socket(this, streamId)
        streams[streamId] = stream

        stream.writeHeaders(headers, outFinished)

        return stream
    }

    override fun accept(): AsyncIterable<out Http2Socket> {
        return incomingSockets
    }

    override suspend fun close() {
        close(ErrorCode.NO_ERROR, ErrorCode.NO_ERROR, null)
    }

    override suspend fun close(throwable: Throwable) {
        close(ErrorCode.PROTOCOL_ERROR, ErrorCode.PROTOCOL_ERROR, throwable)
    }
}

enum class Http2ConnectionMode {
    Client,
    Server,
    ServerSkipConnectionPreface,
}

suspend fun Http2Connection.connect(request: AsyncHttpRequest): Http2Socket {
    val requestBody = request.body
    val socket = connect(request.createHttp2ConnectionHeader(), requestBody == null)
    if (requestBody != null) {
        try {
            requestBody.copy(socket::write)
            socket.closeOutput()
        }
        catch (e: Http2ResetException) {
            if (e.errorCode != ErrorCode.CANCEL)
                throw e
        }
    }
    return socket
}

suspend fun Http2Socket.createHttp2Request(): AsyncHttpRequest {
    val headers = readHeaders()
    return Http2ExchangeCodec.createRequest(headers, ::read)
}

fun AsyncHttpRequest.createHttp2ConnectionHeader(): Headers {
    return Http2ExchangeCodec.createRequestHeaders(this)
}

fun AsyncHttpResponse.createHttp2ConnectionHeader(): Headers {
    return Http2ExchangeCodec.createResponseHeaders(this)
}

fun Http2Connection.acceptHttpAsync(executor: AsyncHttpExecutor) = acceptAsync {
    val request = createHttp2Request()
    val response = try {
        executor(request)
    }
    catch (exception: Exception) {
        StatusCode.INTERNAL_SERVER_ERROR()
    }

    val responseBody = response.body
    writeHeaders(response.createHttp2ConnectionHeader(), responseBody == null)
    if (responseBody != null) {
        responseBody.copy(::write)
        closeOutput()
    }

    endInput(null)
}
