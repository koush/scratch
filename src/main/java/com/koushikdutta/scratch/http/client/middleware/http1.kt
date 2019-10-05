package com.koushikdutta.scratch.http.client.middleware

import com.koushikdutta.scratch.AsyncReader
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.http.AsyncHttpResponse
import com.koushikdutta.scratch.http.Headers
import com.koushikdutta.scratch.http.client.AsyncHttpClientSession


class AsyncHttpTransportMiddleware : AsyncHttpClientMiddleware() {
    override suspend fun exchangeMessages(session: AsyncHttpClientSession): Boolean {
        if (!session.protocol!!.equals("http/1.1", true) && !session.protocol!!.equals("http/1.0", true))
            return false

        val buffer = ByteBufferList()
        buffer.putString(session.request.toMessageString())
        session.socket!!.write(buffer)

        session.socketReader = AsyncReader(session.socket!!::read)
        val statusLine = session.socketReader!!.readScanString("\r\n").trim()
        val headers = Headers()
        while (true) {
            val headerLine = session.socketReader!!.readScanString("\r\n").trim()
            if (headerLine.isEmpty())
                break
            headers.addLine(headerLine)
        }

        session.response = AsyncHttpResponse(statusLine, headers, session.socketReader!!::read)

        return true
    }
}
