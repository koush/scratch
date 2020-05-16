package com.koushikdutta.scratch.http.server

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.async.startSafeCoroutine
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.filters.ChunkedOutputPipe
import com.koushikdutta.scratch.http.*
import com.koushikdutta.scratch.http.client.AsyncHttpDetachedSocket
import com.koushikdutta.scratch.http.client.middleware.AsyncSocketMiddleware
import com.koushikdutta.scratch.http.client.middleware.createContentLengthPipe
import com.koushikdutta.scratch.http.client.middleware.getHttpBody
import com.koushikdutta.scratch.http.http2.Http2Connection
import com.koushikdutta.scratch.http.http2.acceptHttpAsync
import com.koushikdutta.scratch.parser.Parser

open class AsyncHttpResponseScope(val request: AsyncHttpRequest)

typealias AsyncHttpRequestHandler = suspend AsyncHttpResponseScope.() -> AsyncHttpResponse

enum class HttpServerSocketStatus {
    KeepAlive,
    Close,
    Upgrade,
}

class AsyncHttpServer(private val handler: AsyncHttpRequestHandler): AsyncServer {
    private suspend fun acceptHttp2Connection(socket: AsyncSocket, reader: AsyncReader) {
        Http2Connection(socket, false, reader, false)
        .acceptHttpAsync {
            val response = try {
                handler(AsyncHttpResponseScope(request))
            }
            catch (exception: Exception) {
                println("internal server error")
                println(exception)
                StatusCode.INTERNAL_SERVER_ERROR()
            }

            response
        }
        .processMessages()
    }

    internal suspend fun accept(server: AsyncServerSocket<*>?, socket: AsyncSocket, reader: AsyncReader = AsyncReader({socket.read(it)})) {
        while (true) {
            val socketStatus = acceptInternal(server, socket, reader)
            if (socketStatus == HttpServerSocketStatus.KeepAlive)
                continue
            if (socketStatus == HttpServerSocketStatus.Close)
                socket.close()
            return
        }
    }

    suspend fun accept(socket: AsyncSocket, reader: AsyncReader = AsyncReader({socket.read(it)})) = accept(null, socket, reader)

    private suspend fun acceptInternal(server: AsyncServerSocket<*>?, socket: AsyncSocket, reader: AsyncReader = AsyncReader({socket.read(it)})): HttpServerSocketStatus {
        val requestLine = reader.readScanUtf8String("\r\n")
        if (requestLine.isEmpty())
            return HttpServerSocketStatus.Close

        if (requestLine == HTTP2_INITIAL_CONNECTION_PREFACE) {
            Parser.ensureReadString(reader, HTTP2_REMAINING_CONNECTION_PREFACE)
            acceptHttp2Connection(socket, reader)
            // can http2 downgrade?
            return HttpServerSocketStatus.Close
        }

        val requestHeaders = reader.readHeaderBlock()

        val requestBody = getHttpBody(requestHeaders, reader, true)
        val request = AsyncHttpRequest(RequestLine(requestLine), requestHeaders, requestBody)
        val response = try {
            handler(AsyncHttpResponseScope(request))
        }
        catch (throwable: Throwable) {
            println("internal server error")
            println(throwable)
            val headers = Headers()
            headers["Connection"] = "close"
            StatusCode.INTERNAL_SERVER_ERROR(headers = headers)
        }

        if (response is AsyncHttpResponseSwitchingProtocols) {
            sendHeaders(socket, response)
            val switching = object : AsyncHttpDetachedSocket {
                override val socket = socket
                override val socketReader = reader
            }
            val block = response.block
            startSafeCoroutine {
                block(switching)
            }
            return HttpServerSocketStatus.Upgrade
        }

        try {
            // make sure the entire request body has been read before sending the response.
            requestBody.drain()

            require(response.headers.transferEncoding == null) { "Not allowed to set Transfer-Encoding header" }

            var responseBody: AsyncRead

            if (response.body != null) {
                responseBody = response.body!!
                // todo: fixup for jvm
//                if (requestHeaders.acceptEncodingDeflate) {
//                    response.headers.set("Content-Encoding", "deflate")
//                    responseBody = responseBody.pipe(DeflatePipe)
//                }

                if (response.headers.contentLength == null) {
                    response.headers.transferEncoding = "chunked"
                    responseBody = responseBody.pipe(ChunkedOutputPipe)
                }
                else {
                    responseBody = AsyncReader(responseBody).pipe(createContentLengthPipe(response.headers.contentLength!!))
                }
            }
            else {
                val statusCode = StatusCode.values().find { it.code == response.code }
                if (response.headers.contentLength == null && statusCode?.hasBody != false)
                    response.headers.contentLength = 0
                responseBody = { false }
            }

            sendHeaders(socket, response)
            responseBody.copy(socket::write)

            response.sent?.invoke(null)

            if (AsyncSocketMiddleware.isKeepAlive(request, response))
                return HttpServerSocketStatus.KeepAlive
            return HttpServerSocketStatus.Close
        }
        catch (throwable: Throwable) {
            response.sent?.invoke(throwable)
            throw throwable
        }
    }

    override fun listen(server: AsyncServerSocket<*>) = server.acceptAsync {
        try {
            accept(server, this, AsyncReader(this::read))
        }
        catch (throwable: Throwable) {
            // can safely ignore these, as they are transport errors
            close()
        }
    }
    .observeIgnoreErrorsLeaveOpen()

    companion object {
        private const val HTTP2_INITIAL_CONNECTION_PREFACE = "PRI * HTTP/2.0\r\n"
        private const val HTTP2_REMAINING_CONNECTION_PREFACE = "\r\nSM\r\n\r\n"

        private suspend fun sendHeaders(socket: AsyncSocket, response:AsyncHttpResponse) {
            val buffer = ByteBufferList()
            buffer.putUtf8String(response.toMessageString())
            socket::write.drain(buffer)
        }
    }
}
