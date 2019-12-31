package com.koushikdutta.scratch.http.http2

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.buffers.WritableBuffers
import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.http2.okhttp.*
import com.koushikdutta.scratch.http.http2.okhttp.Settings.Companion.DEFAULT_INITIAL_WINDOW_SIZE
import com.koushikdutta.scratch.http.server.AsyncHttpResponseHandler

internal class Http2Stream(val connection: Http2Connection, val streamId: Int, val yielder: Cooperator? = null) : AsyncSocket, AsyncAffinity by connection.socket {
    var headers: List<Header>? = null
    var trailers: List<Header>? = null
    var writeBytesAvailable: Long = connection.peerSettings.initialWindowSize.toLong()
        internal set
    val input = NonBlockingWritePipe {
        acknowledgeData()
    }
    val output = BlockingWritePipe {
        while (it.hasRemaining() && writeBytesAvailable > 0) {
            // perform this write frame on the connection handler
            connection.handler.run {
                val chunkMin = minOf(it.remaining().toLong(), writeBytesAvailable)
                val connectionMin = minOf(connection.writer.maxDataLength().toLong(), connection.writeBytesAvailable)
                val toWrite = minOf(chunkMin, connectionMin)

                writeBytesAvailable -= toWrite
                connection.writeBytesAvailable -= toWrite

                connection.writer.data(false, streamId, it, toWrite.toInt())
                connection.flushData()
            }
        }
    }

    override suspend fun read(buffer: WritableBuffers) = input.read(buffer)

    fun addBytesToWriteWindow(delta: Long) {
        writeBytesAvailable += delta
        if (writeBytesAvailable > 0)
            output.writable()
    }

    // need to serialize writes on a per stream handler
    override suspend fun write(buffer: ReadableBuffers) = output.write(buffer)

    // stops reading the input, tells peer to rst
    var inputClosed = false
    fun closeInput(errorCode: ErrorCode, exception: Exception?) {
        if (inputClosed)
            return
        inputClosed = true
        connection.streams.remove(streamId)
        if (exception == null) {
            input.end()
            return
        }
        input.end(exception)
        connection.handler.post {
            connection.writer.rstStream(streamId, errorCode)
        }
    }

    var outputClosed = false
    suspend fun closeOutput() {
        if (outputClosed)
            return
        outputClosed = true

        connection.handler.run {
            connection.writer.data(true, streamId, null, 0)
            connection.flush()
        }
    }

    // called by user code
    override suspend fun close() {
        await()

        closeInput(ErrorCode.NO_ERROR, null)
        closeOutput()
    }

    fun acknowledgeData() {
        if (unacknowledgedBytes == 0L)
            throw AssertionError("flush called with nothing to flush")
        val delta = unacknowledgedBytes
        unacknowledgedBytes = 0
        connection.writeWindowUpdateLater(streamId, delta)
    }


    var unacknowledgedBytes = 0L
    fun data(inFinished: Boolean, source: BufferedSource) {
        unacknowledgedBytes += source.remaining()
        if (input.write(source) && (unacknowledgedBytes > connection.localSettings.initialWindowSize / 2)) {
            acknowledgeData()
        }
        if (inFinished)
            closeInput(ErrorCode.NO_ERROR, null)
    }
}

typealias Http2ConnectionClose = (exception: Exception?) -> Unit

internal class Http2Connection(val socket: AsyncSocket, val client: Boolean, socketReader: AsyncReader = AsyncReader({socket.read(it)}), readConnectionPreface: Boolean = true, private val requestListener: AsyncHttpResponseHandler? = null) {
    val handler = AsyncHandler({socket.await()})
    var lastGoodStreamId: Int = 0
    var isShutdown = false
    var nextStreamId = if (client) 3 else 2
    val streams = mutableMapOf<Int, Http2Stream>()
    val localSettings = Settings().apply {
        // Flow control was designed more for servers, or proxies than edge clients. If we are a client,
        // set the flow control window to 16MiB.  This avoids thrashing window updates every 64KiB, yet
        // small enough to avoid blowing up the heap.
        if (client) {
            set(Settings.INITIAL_WINDOW_SIZE, DEFAULT_CLIENT_WINDOW_SIZE)
        }
    }
    var peerSettings = DEFAULT_SETTINGS
    private var unacknowledgedBytes = 0L
    var writeBytesAvailable: Long = peerSettings.initialWindowSize.toLong()
        internal set

    var closedCallback: Http2ConnectionClose? = null
    val pinger = Cooperator()
    var awaitingPong = false

    // okhttp uses a blocking sink. after all write operations, gotta flush this sink by actually
    // writing to the socket.
    val sink = ByteBufferList()
    val writer = Http2Writer(sink, client)
    val output = BlockingWritePipe {
        // if the write would consume the window, block until
        // the window size is increased again. this prevents
        // spin loops of zero length writes.
        if (writeBytesAvailable <= 0)
            return@BlockingWritePipe
        socket::write.drain(it)
    }
    val reader = Http2Reader(socket, client, socketReader)
    val readerHandler = object : Http2Reader.Handler {
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
                if (stream.headers == null) {
                    stream.headers = headerBlock
                }
                else if (stream.trailers == null) {
                    stream.trailers = headerBlock
                }
                else {
                    // what are these?
                    failConnection(IOException("received second set of trailers"))
                }

                if (inFinished)
                    stream.input.end()
                stream.yielder!!.resume()
                return
            }

            // incoming request.
            lastGoodStreamId = streamId
            val stream = Http2Stream(this@Http2Connection, streamId)
            stream.headers = headerBlock
            streams[streamId] = stream
            if (inFinished)
                stream.input.end()

            handleIncomingStream(stream)
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
            throw AssertionError("pushPromise not implemented")
        }

        override fun alternateService(streamId: Int, origin: String, protocol: ByteString, host: String, port: Int, maxAge: Long) {
        }
    }

    fun addBytesToWriteWindow(delta: Long) {
        writeBytesAvailable += delta
        if (writeBytesAvailable > 0)
            output.writable()
    }

    fun writePing(
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

        var streamsToClose: Array<Http2Stream>? = null
        if (streams.isNotEmpty()) {
            streamsToClose = streams.values.toTypedArray()
            streams.clear()
        }

        streamsToClose?.forEach { stream ->
            stream.closeOutput()
            stream.closeInput(streamCode, cause)
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

    fun failConnection(exception: Exception?) {
        startSafeCoroutine {
            try {
                if (exception != null)
                    close(ErrorCode.PROTOCOL_ERROR, ErrorCode.PROTOCOL_ERROR, exception)
                else
                    close(ErrorCode.NO_ERROR, ErrorCode.NO_ERROR, null)
            }
            catch (exception: Exception) {
                rethrowUnhandledAsyncException(exception)
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

        startSafeCoroutine {
            try {
                if (readConnectionPreface)
                    reader.readConnectionPreface(readerHandler)
                while (reader.nextFrame(false, readerHandler)) {
                }
            }
            catch (e: Exception) {
                rethrowUnhandledAsyncException(e)
                failConnection(e)
                return@startSafeCoroutine
            }

            failConnection(null)
        }
    }

    internal fun handleIncomingStream(request: Http2Stream) = startSafeCoroutine {
        if (requestListener == null) {
            handler.run {
                writer.rstStream(request.streamId, ErrorCode.REFUSED_STREAM)
                flush()
            }
            return@startSafeCoroutine
        }


        val httpRequest = Http2ExchangeCodec.createRequest(request.headers!!, {request.read(it)})
        val response = requestListener!!(httpRequest)
        val outFinished = response.body == null
        request.outputClosed = outFinished
        handler.run {
            writer.headers(outFinished, request.streamId, Http2ExchangeCodec.createResponseHeaders(response))
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

    suspend fun newStream(request: AsyncHttpRequest, associatedStreamId: Int = 0): Http2Stream {
        val yielder = Cooperator()

        val requestBody = request.body
        val outFinished = requestBody == null

        val stream = handler.run {
            val streamId = nextStreamId
            nextStreamId += 2

            val stream = Http2Stream(this, streamId, yielder)
            streams[streamId] = stream!!

            val headerList = Http2ExchangeCodec.createRequestHeaders(request)

            if (associatedStreamId == 0) {
                writer.headers(outFinished, streamId, headerList)
            } else {
                require(!client) { "client streams shouldn't have associated stream IDs" }
                // HTTP/2 has a PUSH_PROMISE frame.
                writer.pushPromise(associatedStreamId, streamId, headerList)
            }

            flush()
            stream
        }

        if (requestBody != null) {
            requestBody.copy({stream.write(it)})
            stream.closeOutput()
        }


        // this may complete synchronously in a test environment
        if (stream.headers == null)
            yielder.yield()

        return stream
    }

    fun closed(callback: Http2ConnectionClose) {
        closedCallback = callback
    }
}