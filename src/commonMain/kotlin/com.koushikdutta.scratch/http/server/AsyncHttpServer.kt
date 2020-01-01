package com.koushikdutta.scratch.http.server

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.async.startSafeCoroutine
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.filters.ChunkedOutputPipe
import com.koushikdutta.scratch.http.*
import com.koushikdutta.scratch.http.client.middleware.AsyncSocketMiddleware
import com.koushikdutta.scratch.http.client.middleware.createContentLengthPipe
import com.koushikdutta.scratch.http.client.middleware.getHttpBody
import com.koushikdutta.scratch.http.http2.Http2Connection
import com.koushikdutta.scratch.parser.Parser

typealias AsyncHttpResponseHandler = suspend (request: AsyncHttpRequest) -> AsyncHttpResponse

class AsyncHttpServer(private val handler: AsyncHttpResponseHandler) {
    private fun acceptHttp2Connection(socket: AsyncSocket, reader: AsyncReader) {
        Http2Connection(socket, false, reader, false) { request ->
            val response = try {
                handler(request)
            }
            catch (exception: Exception) {
                println("internal server error")
                println(exception)
                AsyncHttpResponse.INTERNAL_SERVER_ERROR()
            }

            if (request.body != null)
                request.body!!.drain()

            response
        }
    }

    private fun reaccept(socket: AsyncSocket, reader: AsyncReader) {
        // when recyclign a socket, clear the coroutine stack trace.
        startSafeCoroutine {
            accept(socket, reader)
        }
    }

    suspend fun accept(socket: AsyncSocket, reader: AsyncReader = AsyncReader({socket.read(it)})) {
        try {
            val requestLine = reader.readScanUtf8String("\r\n")
            if (requestLine.isEmpty()) {
                socket.close()
                return
            }

            if (requestLine == HTTP2_INITIAL_CONNECTION_PREFACE) {
                Parser.ensureReadString(reader, HTTP2_REMAINING_CONNECTION_PREFACE)
                acceptHttp2Connection(socket, reader)
                return
            }

            val requestHeaders = reader.readHeaderBlock()

            val requestBody = getHttpBody(requestHeaders, reader, true)
            val request = AsyncHttpRequest(RequestLine(requestLine), requestHeaders, requestBody)
            val response = try {
                handler(request)
            }
            catch (exception: Exception) {
                println("internal server error")
                println(exception)
                AsyncHttpResponse.INTERNAL_SERVER_ERROR()
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
                    response.headers.contentLength = 0
                    responseBody = { false }
                }

                val buffer = ByteBufferList()
                buffer.putUtf8String(response.toMessageString())
                socket.write(buffer)

                responseBody.copy({socket.write(it)})

                if (AsyncSocketMiddleware.isKeepAlive(request, response))
                    reaccept(socket, reader)
                else
                    socket.close()

                response.sent?.invoke(null)
            }
            catch (throwable: Throwable) {
                response.sent?.invoke(throwable)
                throw throwable
            }
        }
        catch (throwable: Throwable) {
            println("http server error")
            println(throwable)
            socket.close()
        }
    }

    fun listen(server: AsyncServerSocket<*>) = server.acceptAsync {
        accept(this, AsyncReader({this.read(it)}))
    }

    companion object {
        private const val HTTP2_INITIAL_CONNECTION_PREFACE = "PRI * HTTP/2.0\r\n"
        private const val HTTP2_REMAINING_CONNECTION_PREFACE = "\r\nSM\r\n\r\n"
    }
}
