package com.koushikdutta.scratch.http.client.executor

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.collections.Multimap
import com.koushikdutta.scratch.collections.getFirst
import com.koushikdutta.scratch.collections.parseCommaDelimited
import com.koushikdutta.scratch.event.milliTime
import com.koushikdutta.scratch.filters.ChunkedOutputPipe
import com.koushikdutta.scratch.http.*
import com.koushikdutta.scratch.http.client.AsyncHttpDetachedSocket
import com.koushikdutta.scratch.http.client.createContentLengthPipe
import com.koushikdutta.scratch.http.client.createEndWatcher
import com.koushikdutta.scratch.http.client.getHttpBodyOrNull
import com.koushikdutta.scratch.http.http2.okhttp.Protocol

class AsyncHttpClientSwitchingProtocols(val responseHeaders: Headers, override val socket: AsyncSocket, override val socketReader: AsyncReader): Exception(), AsyncHttpDetachedSocket

class AsyncHttpSocketExecutor(val socket: AsyncSocket, val reader: AsyncReader = AsyncReader(socket::read)): AsyncHttpClientExecutor {
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
                Promise {
                    socket.close()
                }
            }
            return field
        }
        internal set
    var isResponseEnded = true
        internal set

    override suspend operator fun invoke(request: AsyncHttpRequest): AsyncHttpResponse {
        if (!isAlive)
            throw IOException("The previous response closed the socket")

        if (!isResponseEnded)
            throw IOException("The previous response body was not fully read")

        isResponseEnded = true
        isAlive = true

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
            requestBody = { false }
        }

        buffer.putUtf8String(request.toMessageString())
        socket::write.drain(buffer)
        requestBody.copy(socket::write)
        request.close()

        val statusLine = reader.readHttpHeaderLine().trim()
        val headers = reader.readHeaderBlock()
        val responseLine = ResponseLine(statusLine)

        if (responseLine.code == StatusCode.SWITCHING_PROTOCOLS.code)
            throw AsyncHttpClientSwitchingProtocols(headers, socket, reader)

        val statusCode = StatusCode.values().find { it.code == responseLine.code }
        val body = if (statusCode?.hasBody == false)
            null
        else {
            getHttpBodyOrNull(headers, reader, false)
        }

        val responseBody = if (body != null) {
            isResponseEnded = false
            createEndWatcher(body) {
                isResponseEnded = true
            }
        }
        else {
            null
        }

        val response = AsyncHttpResponse(ResponseLine(statusLine), headers, responseBody)
        isAlive = isKeepAlive(request, response)
        val keepAlive = parseCommaDelimited(headers["Connection"] ?: "")
        // use a default keepalive timeout of 5 seconds.
        val timeout = keepAlive.getFirst("timeout")?.toInt() ?: 5
        this.timeout = milliTime() + timeout * 1000
        return response
    }

    companion object {
        fun isKeepAlive(request: AsyncHttpRequest, response: AsyncHttpResponse): Boolean {
            return isKeepAlive(response.protocol, response.headers) && isKeepAlive(request.protocol, request.headers)
        }

        fun isKeepAlive(protocol: String, headers: Headers): Boolean {
            // connection is always keep alive as this is an http/1.1 client
            val connection = headers.get("Connection") ?: return protocol.toLowerCase() == Protocol.HTTP_1_1.toString()
            return "keep-alive".equals(connection, ignoreCase = true)
        }
    }
}