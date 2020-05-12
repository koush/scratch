package com.koushikdutta.scratch.http.client.middleware

import com.koushikdutta.scratch.http.client.AsyncHttpClientSession
import com.koushikdutta.scratch.http.http2.okhttp.Http2ExchangeCodec
import com.koushikdutta.scratch.http.http2.Http2Stream
import com.koushikdutta.scratch.http.http2.okhttp.Protocol

class AsyncHttp2TransportMiddleware: AsyncHttpTransportMiddleware() {
    override suspend fun exchangeMessages(session: AsyncHttpClientSession): Boolean {
        if (session.protocol != Protocol.HTTP_2.toString())
            return false

        session.response = Http2ExchangeCodec.createResponse(session.socket!!.socket as Http2Stream)
        return true
    }
}
