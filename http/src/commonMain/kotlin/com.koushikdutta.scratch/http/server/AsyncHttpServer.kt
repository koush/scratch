package com.koushikdutta.scratch.http.server

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.async.startSafeCoroutine
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.filters.ChunkedOutputPipe
import com.koushikdutta.scratch.http.*
import com.koushikdutta.scratch.http.client.AsyncHttpExecutor
import com.koushikdutta.scratch.http.client.createContentLengthPipe
import com.koushikdutta.scratch.http.client.getHttpBody
import com.koushikdutta.scratch.http.http2.Http2Connection
import com.koushikdutta.scratch.http.http2.Http2ConnectionMode
import com.koushikdutta.scratch.http.http2.acceptHttpAsync
import com.koushikdutta.scratch.http.Parser

enum class HttpServerSocketStatus {
    KeepAlive,
    Close,
    Upgrade,
}

class AsyncHttpServer(private val executor: AsyncHttpExecutor): AsyncServer {
    private suspend fun acceptHttp2Connection(socket: AsyncSocket, reader: AsyncReader) {
        // connection preface has been negotiated
        Http2Connection.upgradeHttp2Connection(socket, Http2ConnectionMode.ServerSkipConnectionPreface, reader)
        .acceptHttpAsync {
            val response = try {
                executor(it)
            }
            catch (exception: Exception) {
                println("internal server error")
                println(exception)
                StatusCode.INTERNAL_SERVER_ERROR()
            }

            response
        }
        .awaitClose()
    }

    private suspend fun acceptLoop(socket: AsyncSocket, reader: AsyncReader = AsyncReader(socket)) {
        while (true) {
            val socketStatus = acceptInternal(socket, reader)
            if (socketStatus == HttpServerSocketStatus.KeepAlive)
                continue
            if (socketStatus == HttpServerSocketStatus.Close)
                socket.close()
            return
        }
    }

    suspend fun accept(socket: AsyncSocket, reader: AsyncReader = AsyncReader(socket)) = acceptLoop(socket, reader)

    private suspend fun acceptInternal(socket: AsyncSocket, reader: AsyncReader = AsyncReader(socket)): HttpServerSocketStatus {
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
            executor(request)
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
            val block = response.block
            startSafeCoroutine {
                block(socket)
            }
            return HttpServerSocketStatus.Upgrade
        }

        try {
            // make sure the entire request body has been read before sending the response.
            requestBody.siphon()

            require(response.headers.transferEncoding == null) { "Not allowed to set Transfer-Encoding header" }

            var responseBody: AsyncRead

            if (response.body != null) {
                responseBody = response.body!!

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
                responseBody = AsyncRead { false }
            }

            sendHeaders(socket, response)
            responseBody.copy(socket)

            response.close(null)

            if (KeepAlive.isKeepAlive(request, response))
                return HttpServerSocketStatus.KeepAlive
            return HttpServerSocketStatus.Close
        }
        catch (throwable: Throwable) {
            response.close(throwable)
            throw throwable
        }
    }

    override fun <T : AsyncSocket> listen(server: AsyncServerSocket<T>) = server.acceptAsync {
        try {
            acceptLoop(this, AsyncReader(this))
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
            socket.drain(buffer as ReadableBuffers)
        }
    }
}
