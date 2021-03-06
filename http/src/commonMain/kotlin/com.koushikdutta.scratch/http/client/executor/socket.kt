package com.koushikdutta.scratch.http.client.executor

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.async.launch
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.collections.getFirst
import com.koushikdutta.scratch.collections.parseCommaDelimited
import com.koushikdutta.scratch.event.milliTime
import com.koushikdutta.scratch.filters.ChunkedOutputPipe
import com.koushikdutta.scratch.http.*
import com.koushikdutta.scratch.http.client.createContentLengthPipe
import com.koushikdutta.scratch.http.client.createEndWatcher
import com.koushikdutta.scratch.http.client.getHttpBodyOrNull
import com.koushikdutta.scratch.http.http2.okhttp.Protocol

class AsyncHttpClientSwitchingProtocols(val responseHeaders: Headers, val socket: AsyncSocket): Exception()

class AsyncHttpSocketExecutor(val socket: AsyncSocket, val reader: AsyncReader = AsyncReader(socket)): AsyncHttpClientExecutor {
    override val affinity = socket
    private val buffer = ByteBufferList()
    private var timeout = Long.MAX_VALUE
    var isAlive: Boolean = true
        get() {
            // check if dead
            if (!field)
                return field

            // validate timeout
            field = timeout > milliTime()
            if (!field) {
                socket.launch {
                    socket.close()
                }
            }
            return field
        }
        internal set
    var isResponseEnded = true
        internal set

    override suspend operator fun invoke(request: AsyncHttpRequest): AsyncHttpResponse {
        affinity.await()

        if (!isAlive)
            throw IOException("The previous response closed the socket")

        if (!isResponseEnded)
            throw IOException("The previous response body was not fully read")

        isResponseEnded = false
        isAlive = true

        request.headers.transferEncoding = null

        var requestBody: AsyncRead
        if (request.body != null) {
            requestBody = request.body!!
            if (request.headers.contentLength == null) {
                request.headers.transferEncoding = "chunked"
                requestBody = requestBody.pipe(ChunkedOutputPipe)
            }
            else {
                requestBody = AsyncReader(requestBody).pipe(createContentLengthPipe(request.headers.contentLength!!))
            }
        }
        else {
            requestBody = AsyncRead { false }
        }

        buffer.putUtf8String(request.toMessageString())
        socket.drain(buffer as ReadableBuffers)
        requestBody.copy(socket, buffer)
        request.close()

        val statusLine = reader.readHttpHeaderLine().trim()
        val headers = reader.readHeaderBlock()
        val responseLine = ResponseLine(statusLine)

        if (responseLine.code == StatusCode.SWITCHING_PROTOCOLS.code)
            throw AsyncHttpClientSwitchingProtocols(headers, socket)

        val parsedResponseLine = ResponseLine(statusLine)
        isAlive = isKeepAlive(request, parsedResponseLine.protocol, headers)

        val statusCode = StatusCode.values().find { it.code == responseLine.code }
        val body = if (statusCode?.hasBody == false)
            null
        else {
            getHttpBodyOrNull(headers, reader, false)
        }

        val responseBody = if (body != null) {
            createEndWatcher(body) {
                isResponseEnded = true

                if (isAlive)
                    observeKeepAlive()
            }
        }
        else {
            isResponseEnded = true
            if (isAlive)
                observeKeepAlive()
            null
        }

        val response = AsyncHttpResponse(parsedResponseLine, headers, responseBody)
        if (isAlive) {
            val keepAlive = parseCommaDelimited(headers["Connection"] ?: "")
            // use a default keepalive timeout of 5 seconds.
            val timeout = keepAlive.getFirst("timeout")?.toInt() ?: 5
            this.timeout = milliTime() + timeout * 1000
        }
        return response
    }

    private fun observeKeepAlive() = affinity.launch {
        // this coroutine may launch after the socket is immediately reused.
        // watch for this happening and bail on the keepalive observation.
        if (!isResponseEnded)
            return@launch

        try {
            reader.readBuffer()
            isAlive = false
        }
        catch (doubleRead: AsyncDoubleReadException) {
            // double read can be ignored. it will occur if the keepalive socket is reused.
        }
        catch (throwable: Throwable) {
            isAlive = false
        }
    }

    companion object {
        fun isKeepAlive(request: AsyncHttpRequest, responseProtocol: String, responseHeaders: Headers): Boolean {
            return isKeepAlive(responseProtocol, responseHeaders) && isKeepAlive(request.protocol, request.headers)
        }

        fun isKeepAlive(protocol: String, headers: Headers): Boolean {
            // connection is always keep alive as this is an http/1.1 client
            val connection = headers.get("Connection") ?: return protocol.toLowerCase() == Protocol.HTTP_1_1.toString()
            return "keep-alive".equals(connection, ignoreCase = true)
        }
    }
}