package com.koushikdutta.scratch.http.client.middleware

import com.koushikdutta.scratch.http.client.AsyncHttpClientSession
import com.koushikdutta.scratch.http.http2.okhttp.Http2ExchangeCodec
import com.koushikdutta.scratch.http.http2.Http2Socket
import com.koushikdutta.scratch.http.http2.okhttp.Protocol

class AsyncHttp2TransportMiddleware: AsyncHttpTransportMiddleware() {
    override suspend fun exchangeMessages(session: AsyncHttpClientSession): Boolean {
        if (session.transport?.protocol != Protocol.HTTP_2.toString())
            return false

        val socket = session.transport!!.socket as Http2Socket
        val headers = socket.readHeaders()
        if (socket.pushPromise)
            headers["X-Scratch-PushPromise"] = "true"
        session.response = Http2ExchangeCodec.createResponse(headers, socket)
        return true
    }
}
