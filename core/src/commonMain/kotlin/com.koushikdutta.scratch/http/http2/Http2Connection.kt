package com.koushikdutta.scratch.http.http2

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.async.AsyncHandler
import com.koushikdutta.scratch.async.startSafeCoroutine
import com.koushikdutta.scratch.atomic.AtomicBoolean
import com.koushikdutta.scratch.atomic.AtomicThrowingLock
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.buffers.WritableBuffers
import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.AsyncHttpResponse
import com.koushikdutta.scratch.http.Headers
import com.koushikdutta.scratch.http.http2.okhttp.*
import com.koushikdutta.scratch.http.http2.okhttp.Settings.Companion.DEFAULT_INITIAL_WINDOW_SIZE
import com.koushikdutta.scratch.http.server.AsyncHttpRequestHandler
import com.koushikdutta.scratch.http.server.AsyncHttpResponseScope

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

class Http2Socket internal constructor(val connection: Http2Connection, val streamId: Int) : AsyncSocket, AsyncAffinity by connection.socket {
    internal val incomingHeaders = AsyncQueue<Headers>()
    suspend fun readHeaders() = incomingHeaders.iterator().next()
    private var writeBytesAvailable: Long = connection.peerSettings.initialWindowSize.toLong()
    internal val input = NonBlockingWritePipe {
        acknowledgeData()
    }
    private val output = BlockingWritePipe {
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

    override suspend fun write(buffer: ReadableBuffers) = output.write(buffer)

    internal fun endInput(exception: Exception?): Boolean {
        return if (exception != null) {
            incomingHeaders.end(exception)
            input.end(exception)
        }
        else {
            incomingHeaders.end()
            input.end()
        }
    }

    // stops reading the input, tells peer to rst
    internal fun endInput(errorCode: ErrorCode, exception: Exception?) {
        // input may have already ended.
        if (!endInput(exception))
            return
        connection.streams.remove(streamId)
        connection.handler.post {
            connection.writer.rstStream(streamId, errorCode)
        }
    }

    internal val outputClosed = AtomicBoolean()
    suspend fun closeOutput() {
        if (outputClosed.getAndSet(true))
            return

        connection.handler.run {
            connection.writer.data(true, streamId, null, 0)
            connection.flush()
        }
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

typealias Http2ConnectionClose = (exception: Exception?) -> Unit

class Http2Connection(val socket: AsyncSocket, val client: Boolean, socketReader: AsyncReader = AsyncReader({socket.read(it)}), private val readConnectionPreface: Boolean = true, private val requestListener: AsyncHttpRequestHandler? = null) {
    internal val handler = AsyncHandler(socket)
    internal var lastGoodStreamId: Int = 0
    private var isShutdown = false
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
    internal val reader = Http2Reader(socket, client, socketReader)
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

                if (inFinished) {

                    stream.endInput(null)
                }
                return
            }

            // incoming request.
            lastGoodStreamId = streamId
            val stream = Http2Socket(this@Http2Connection, streamId)
            streams[streamId] = stream
            if (inFinished)
                stream.input.end()

            handleIncomingStream(headerBlock.toHeaders(), stream)
        }

        override fun rstStream(streamId: Int, errorCode: ErrorCode) {
            val stream = streams.remove(streamId)
            stream?.input?.end(IOException("http2 stream reset: $errorCode"))
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
            val streamsToNotify = when {
                delta == 0L || streams.isEmpty() -> null // No adjustment is necessary.
                else -> streams.values.toTypedArray()
            }

            peerSettings = newPeerSettings

            handler.post {
                try {
                    writer.applyAndAckSettings(newPeerSettings)
                    flush()
                } catch (e: IOException) {
                    failConnection(e)
                }
            }

            if (streamsToNotify != null) {
                for (stream in streamsToNotify!!) {
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
            failConnection(IOException("http2 goAway received, shutting down"))
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

        override fun pushPromise(streamId: Int, promisedStreamId: Int, requestHeaders: List<Header>) {
            handler.post {
                writer.rstStream(streamId, ErrorCode.CANCEL)
            }
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
                failConnection(IOException("missing pong"))
                return
            }
            awaitingPong = true
        }

        handler.post {
            try {
                writer.ping(reply, payload1, payload2)
                flush()
            } catch (e: IOException) {
                failConnection(e)
            }
        }
    }

    fun ping() {
        writePing(false, 0x4f4b6f6b /* "OKok" */, -0xf607257 /* donut */)
    }


    internal suspend fun close(connectionCode: ErrorCode, streamCode: ErrorCode, cause: Exception?) {
        socket.await()
        shutdown(connectionCode)

        var streamsToClose: Array<Http2Socket>? = null
        if (streams.isNotEmpty()) {
            streamsToClose = streams.values.toTypedArray()
            streams.clear()
        }

        streamsToClose?.forEach { stream ->
            stream.closeOutput()
            stream.endInput(streamCode, cause)
        }

        // Close the socket to break out the reader thread, which will clean up after itself.
        socket.close()
    }


    private fun shutdown(statusCode: ErrorCode) {
        handler.post {
            if (isShutdown)
                return@post
            isShutdown = true
            // TODO: propagate exception message into debugData.
            // TODO: configure a timeout on the reader so that it doesn’t block forever.
            writer.goAway(lastGoodStreamId, statusCode, ByteArray(0))
        }
    }

    private fun failConnection(exception: Exception?) {
        startSafeCoroutine {
            try {
                if (exception != null)
                    close(ErrorCode.PROTOCOL_ERROR, ErrorCode.PROTOCOL_ERROR, exception)
                else
                    close(ErrorCode.NO_ERROR, ErrorCode.NO_ERROR, null)
            }
            catch (exception: Exception) {
            }
        }
    }

    companion object {
        const val DEFAULT_CLIENT_WINDOW_SIZE = 16 * 1024 * 1024
        val DEFAULT_SETTINGS = Settings().apply {
            set(Settings.INITIAL_WINDOW_SIZE, DEFAULT_INITIAL_WINDOW_SIZE)
            set(Settings.MAX_FRAME_SIZE, Http2.INITIAL_MAX_FRAME_SIZE)
        }
    }

    init {
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

    private val processLock = AtomicThrowingLock { IOException("Http2Connection has already started") }
    suspend fun processMessages() = processLock {
        try {
            if (readConnectionPreface)
                reader.readConnectionPreface(readerHandler)
            while (true) {
                reader.nextFrame(false, readerHandler)
            }
        }
        catch (e: Exception) {
            failConnection(e)
            return
        }
    }

    fun processMessagesAsync() = Promise(::processMessages)

    internal fun handleIncomingStream(requestHeaders: Headers, request: Http2Socket) = startSafeCoroutine {
        if (requestListener == null) {
            handler.run {
                writer.rstStream(request.streamId, ErrorCode.REFUSED_STREAM)
                flush()
            }
            return@startSafeCoroutine
        }


        val httpRequest = Http2ExchangeCodec.createRequest(requestHeaders, request::read)
        val response = requestListener!!(AsyncHttpResponseScope(httpRequest))
        val outFinished = response.body == null
        if (outFinished)
            request.outputClosed.set(true)
        handler.run {
            writer.headers(outFinished, request.streamId, response.createHttp2ConnectionHeader().toHeaders())
            flush()
        }

        if (!outFinished) {
            response.body!!.copy({request.write(it)})
            request.closeOutput()
        }
    }

    internal fun writeWindowUpdateLater(streamId: Int, unacknowledgedBytes: Long) = handler.post {
        if (unacknowledgedBytes == 0L)
            return@post
        writer.windowUpdate(streamId, unacknowledgedBytes)
        flush()
    }

    internal suspend fun flush() {
        socket::write.drain(sink)
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
        val stream = handler.run {
            val streamId = nextStreamId
            nextStreamId += 2

            val stream = Http2Socket(this, streamId)
            streams[streamId] = stream

            writer.headers(outFinished, streamId, headers.toHeaders())

            flush()
            stream
        }

        return stream
    }
}

suspend fun Http2Connection.connect(request: AsyncHttpRequest): Http2Socket {
    val requestBody = request.body
    val socket = connect(request.createHttp2ConnectionHeader(), requestBody == null)
    if (requestBody != null) {
        requestBody.copy(socket::write)
        socket.closeOutput()
    }
    return socket
}

fun AsyncHttpRequest.createHttp2ConnectionHeader(): Headers {
    return Http2ExchangeCodec.createRequestHeaders(this)
}

fun AsyncHttpResponse.createHttp2ConnectionHeader(): Headers {
    return Http2ExchangeCodec.createResponseHeaders(this)
}
