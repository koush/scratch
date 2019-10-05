package com.koushikdutta.scratch.http.http2

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.buffers.WritableBuffers
import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.http2.Settings.Companion.DEFAULT_INITIAL_WINDOW_SIZE
import java.io.IOException

class Http2Stream(val connection: Http2Connection, val streamId: Int, val yielder: Cooperator? = null) : AsyncSocket {
    val handler = AsyncHandler(connection.socket::await)
    var headers: List<Header>? = null
    var writeBytesMaximum: Long = connection.peerSettings.initialWindowSize.toLong()
        internal set
    val writeYielder = Cooperator()
    val writeBuffer = ByteBufferList()
    val input = object : NonBlockingWritePipe() {
        override fun writable() {
            acknowledgeData()
        }
    }

    override suspend fun await() {
        connection.socket.await()
    }

    override suspend fun read(buffer: WritableBuffers): Boolean {
        return input.read(buffer)
    }

    fun addBytesToWriteWindow(delta: Long) {
        writeBytesMaximum += delta
        if (writeBytesMaximum > 0)
            writeYielder.resume()
    }

    // need to serialize writes on a per stream handler
    override suspend fun write(buffer: ReadableBuffers) = handler.run {
        while (buffer.hasRemaining()) {
            // check write window, and yield if necessary
            if (writeBytesMaximum <= 0)
                writeYielder.yield()

            var toWrite = minOf(buffer.remaining().toLong(), connection.writeBytesMaximum).toInt()
            toWrite = minOf(toWrite, connection.writer.maxDataLength())

            writeBytesMaximum -= toWrite
            buffer.get(writeBuffer, toWrite)

            // perform this write frame on the connection handler
            connection.handler.run {
                connection.writer.data(buffer.isEmpty, streamId, writeBuffer, writeBuffer.remaining())
                connection.flush()
            }
        }
    }

    override suspend fun close() {
    }

    fun acknowledgeData() {
        if (unacknowledgedBytes == 0L)
            throw AssertionError("flush called with nothing to flush")
        connection.writeWindowUpdateLater(streamId, unacknowledgedBytes)
        unacknowledgedBytes = 0
    }


    var unacknowledgedBytes = 0L
    fun data(inFinished: Boolean, source: BufferedSource) {
        unacknowledgedBytes += source.remaining()
        if (input.write(source) && unacknowledgedBytes > connection.localSettings.initialWindowSize / 2) {
            acknowledgeData()
        }
        if (inFinished) {
            input.end()
            connection.streams.remove(streamId)
        }
    }
}

typealias Http2ConnectionClose = (exception: Exception?) -> Unit

class Http2Connection(val socket: AsyncSocket, val client: Boolean) {
    val incoming = AsyncDequeueIterator<Http2Stream>()
    val handler = AsyncHandler(socket::await)
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
    var writeBytesMaximum: Long = peerSettings.initialWindowSize.toLong()
        private set

    var closedCallback: Http2ConnectionClose? = null
    val pinger = Cooperator()
    var awaitingPong = false

    // okhttp uses a blocking sink. after all write operations, gotta flush this sink by actually
    // writing to the socket.
    val sink = ByteBufferList()
    val writer = Http2Writer(sink, client)
    val reader = Http2Reader(socket, client)
    val readerHandler = object : Http2Reader.Handler {
        override fun data(inFinished: Boolean, streamId: Int, source: BufferedSource, length: Int) {
            writeWindowUpdateLater(0, length.toLong())
            // length isn't used here as the source is simply a buffer of length
            val stream = streams[streamId]!!
            // todo: stream null?
            stream.data(inFinished, source)
        }

        override fun headers(inFinished: Boolean, streamId: Int, associatedStreamId: Int, headerBlock: List<Header>) {
            if (streams.contains(streamId)) {
                val stream = streams[streamId]!!
                stream.headers = headerBlock
                if (inFinished)
                    stream.input.end()
                stream.yielder!!.resume()
                return
            }

            // push stream
            val stream = Http2Stream(this@Http2Connection, streamId)
            streams[streamId] = stream
            if (inFinished)
                stream.input.end()
            incoming.add(stream)
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
                    closeConnection(e)
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
            closeConnection(IOException("http2 goAway received, shutting down"))
        }

        override fun windowUpdate(streamId: Int, windowSizeIncrement: Long) {
            if (streamId == 0) {
                writeBytesMaximum += windowSizeIncrement
            }
            else {
                val stream = streams[streamId]!!
                stream.writeBytesMaximum += windowSizeIncrement
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


    fun writePing(
            reply: Boolean,
            payload1: Int,
            payload2: Int
    ) {
        if (!reply) {
            if (awaitingPong) {
                closeConnection(java.lang.Exception("missing pong"))
                return
            }
            awaitingPong = true
        }

        handler.post {
            try {
                writer.ping(reply, payload1, payload2)
                flush()
            } catch (e: IOException) {
                closeConnection(e)
            }
        }
    }

    fun ping() {
        writePing(false, 0x4f4b6f6b /* "OKok" */, -0xf607257 /* donut */)
    }

    fun closeConnection(exception: Exception?) {
        val streamException = exception ?: IOException("http2 connection shut down")
        for (stream in streams.values) {
            stream.input.end(streamException)
        }
        streams.clear()

        if (closedCallback != null) {
            val closed = closedCallback!!
            closedCallback = null
            closed(exception)
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
        async {
            try {
                reader.readConnectionPreface(readerHandler)
                while (reader.nextFrame(false, readerHandler)) {
                }
            }
            catch (e: Exception) {
                closeConnection(e)
                return@async
            }

            closeConnection(null)
        }

        handler.post {
            writer.connectionPreface()
            writer.settings(localSettings)
            val windowSize = localSettings.initialWindowSize
            if (windowSize != DEFAULT_INITIAL_WINDOW_SIZE) {
                writer.windowUpdate(0, (windowSize - DEFAULT_INITIAL_WINDOW_SIZE).toLong())
            }
        }
    }

    suspend fun accept(): Http2Stream {
        return incoming.iterator().next()
    }

    internal fun writeWindowUpdateLater(streamId: Int, unacknowledgedBytes: Long) = handler.post {
        if (unacknowledgedBytes == 0L)
            return@post
        writer.windowUpdate(streamId, unacknowledgedBytes)
        flush()
    }

    internal suspend fun flush() {
        socket.write(sink)
    }

    suspend fun newStream(request: AsyncHttpRequest, outFinished: Boolean, associatedStreamId: Int = 0): Http2Stream {
        val yielder = Cooperator()

        val stream = handler.run {
            val streamId = nextStreamId
            nextStreamId += 2

            val stream = Http2Stream(this, streamId, yielder)
            streams[streamId] = stream

            val headerList = Http2ExchangeCodec.createRequest(request)

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

        yielder.yield()

        return stream
    }

    fun closed(callback: Http2ConnectionClose) {
        closedCallback = callback
    }
}