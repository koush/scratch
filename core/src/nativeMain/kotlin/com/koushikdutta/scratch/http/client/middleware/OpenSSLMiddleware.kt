package com.koushikdutta.scratch.http.client.middleware

import com.koushikdutta.scratch.AsyncSocket
import com.koushikdutta.scratch.event.AsyncEventLoop
import com.koushikdutta.scratch.http.client.AsyncHttpClient
import com.koushikdutta.scratch.http.client.AsyncHttpClientSession
import com.koushikdutta.scratch.http.client.AsyncHttpClientTransport
import com.koushikdutta.scratch.http.http2.okhttp.Protocol
import com.koushikdutta.scratch.tls.*

open class OpenSSLMiddleware(eventLoop: AsyncEventLoop, context: SSLContext = getDefaultSSLContext()) : AsyncTlsSocketMiddleware(eventLoop, context) {
    fun install(client: AsyncHttpClient) {
        client.middlewares.add(0, this)
    }

    override fun configureEngine(engine: SSLEngine) {
        if (engine !is SSLEngineImpl)
            return
        engine.setAlpnProtocols(listOf("h2", "http/1.1"))
    }

    override suspend fun wrapSocket(session: AsyncHttpClientSession, socket: AsyncSocket, host: String, port: Int): AsyncSocket {
        throw Throwable("wrapSocket in OpenSSLMiddleware should be unreachable")
    }

    override suspend fun createTransport(session: AsyncHttpClientSession, host: String, port: Int): AsyncHttpClientTransport {
        val socket = connectInternal(session, host, port)
        val tlsSocket = wrapForTlsSocket(session, socket, host, port)
        if (tlsSocket.engine !is SSLEngineImpl)
            return AsyncHttpClientTransport(tlsSocket)
        if ("h2" == tlsSocket.engine.getNegotiatedAlpnProtocol())
            return AsyncHttpClientTransport(manageHttp2Connection(session, host, port, tlsSocket), protocol = Protocol.HTTP_2.toString())
        return AsyncHttpClientTransport(tlsSocket)
    }
}