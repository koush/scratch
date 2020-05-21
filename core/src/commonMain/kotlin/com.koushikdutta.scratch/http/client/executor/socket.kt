package com.koushikdutta.scratch.http.client.executor

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.http.*
import com.koushikdutta.scratch.http.client.middleware.createEndWatcher
import com.koushikdutta.scratch.http.client.middleware.getHttpBodyOrNull
import com.koushikdutta.scratch.http.http2.okhttp.Protocol

class AsyncHttpSocketExecutor(val socket: AsyncSocket, val reader: AsyncReader = AsyncReader(socket::read)) {
    private val buffer = ByteBufferList()
    var isAlive = true
        internal set
    var isResponseEnded = true
        internal set

    suspend operator fun invoke(request: AsyncHttpRequest): AsyncHttpResponse {
        if (!isAlive)
            throw IOException("The previous response closed the socket")

        if (!isResponseEnded)
            throw IOException("The previous response body was not fully read")

        isResponseEnded = true
        isAlive = true

        buffer.putUtf8String(request.toMessageString())
        socket::write.drain(buffer)
        request.body?.copy(socket::write)
        request.close()

        val statusLine = reader.readHttpHeaderLine().trim()
        val headers = reader.readHeaderBlock()
        val responseLine = ResponseLine(statusLine)

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

        val response = AsyncHttpResponse(ResponseLine(statusLine), headers, responseBody) {
            socket.close()
        }
        isAlive = isKeepAlive(request, response)
        return response
    }

    companion object {
        private fun isKeepAlive(request: AsyncHttpRequest, response: AsyncHttpResponse): Boolean {
            return isKeepAlive(response.protocol, response.headers) && isKeepAlive(request.protocol, request.headers)
        }

        private fun isKeepAlive(protocol: String, headers: Headers): Boolean {
            // connection is always keep alive as this is an http/1.1 client
            val connection = headers.get("Connection") ?: return protocol.toLowerCase() == Protocol.HTTP_1_1.toString()
            return "keep-alive".equals(connection, ignoreCase = true)
        }
    }
}