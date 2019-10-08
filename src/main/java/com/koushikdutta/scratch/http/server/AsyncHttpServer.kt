package com.koushikdutta.scratch.http.server

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.filters.ChunkedOutputPipe
import com.koushikdutta.scratch.filters.DeflatePipe
import com.koushikdutta.scratch.http.*
import com.koushikdutta.scratch.http.client.middleware.AsyncSocketMiddleware
import com.koushikdutta.scratch.http.client.middleware.createContentLengthPipe
import com.koushikdutta.scratch.http.client.middleware.getHttpBody

typealias AsyncHttpResponseHandler = suspend (request: AsyncHttpRequest) -> AsyncHttpResponse

class AsyncHttpServer(private val handler: AsyncHttpResponseHandler) {
    fun accept(socket: AsyncSocket, reader: AsyncReader = AsyncReader(socket::read)) {
        async {
            try {
                val requestLine = reader.readScanString("\r\n")
                if (requestLine.isEmpty()) {
                    socket.close()
                    return@async
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

                // make sure the entire request body has been read before sending the response.
                requestBody.drain()

                require(response.headers.transferEncoding == null) { "Not allowed to set Transfer-Encoding header" }

                var responseBody: AsyncRead

                if (response.body != null) {
                    responseBody = response.body!!
                    if (requestHeaders.acceptEncodingDeflate) {
                        response.headers.set("Content-Encoding", "deflate")
                        responseBody = responseBody.pipe(DeflatePipe)
                    }

                    if (response.headers.contentLength == null) {
                        response.headers.transferEncoding = "chunked"
                        responseBody = responseBody.pipe(ChunkedOutputPipe)
                    }
                    else {
                        responseBody = createContentLengthPipe(response.headers.contentLength!!, AsyncReader(responseBody))
                    }
                }
                else {
                    response.headers.contentLength = 0
                    responseBody = { false }
                }

                val buffer = ByteBufferList()
                buffer.putString(response.toMessageString())
                socket.write(buffer)

                responseBody.copy(socket::write)

                if (AsyncSocketMiddleware.isKeepAlive(request, response))
                    accept(socket, reader)
                else
                    socket.close()
            }
            catch (exception: Exception) {
                println("http server error")
                println(exception)
                socket.close()
            }
        }
    }

    fun listen(server: AsyncServerSocket) = server.accept().receive {
        accept(it)
    }
}
