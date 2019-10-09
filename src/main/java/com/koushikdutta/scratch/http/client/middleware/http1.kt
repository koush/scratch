package com.koushikdutta.scratch.http.client.middleware

import com.koushikdutta.scratch.AsyncRead
import com.koushikdutta.scratch.AsyncReader
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.copy
import com.koushikdutta.scratch.filters.ChunkedOutputPipe
import com.koushikdutta.scratch.http.*
import com.koushikdutta.scratch.http.client.AsyncHttpClientSession
import com.koushikdutta.scratch.http.http2.okhttp.Protocol
import com.koushikdutta.scratch.pipe


open class AsyncHttpTransportMiddleware : AsyncHttpClientMiddleware() {
    override suspend fun exchangeMessages(session: AsyncHttpClientSession): Boolean {
        val protocol = session.protocol!!.toLowerCase()
        if (protocol != Protocol.HTTP_1_0.toString() && protocol != Protocol.HTTP_1_1.toString())
            return false

        require(session.request.headers.transferEncoding == null) { "Not allowed to set Transfer-Encoding header" }

        val request = session.request
        var requestBody: AsyncRead
        if (request.body != null) {
            requestBody = request.body!!
            if (request.headers.contentLength == null) {
                request.headers.transferEncoding = "chunked"
                requestBody = requestBody.pipe(ChunkedOutputPipe)
            }
            else {
                requestBody = createContentLengthPipe(request.headers.contentLength!!, AsyncReader(requestBody))
            }
        }
        else {
            requestBody = { false }
        }

        val buffer = ByteBufferList()
        buffer.putString(session.request.toMessageString())
        session.socket!!.write(buffer)

        requestBody.copy(session.socket!!::write)

        val statusLine = session.socketReader!!.readScanString("\r\n").trim()
        val headers = session.socketReader!!.readHeaderBlock()

        session.response = AsyncHttpResponse(ResponseLine(statusLine), headers, session.socketReader!!::read)

        return true
    }
}
