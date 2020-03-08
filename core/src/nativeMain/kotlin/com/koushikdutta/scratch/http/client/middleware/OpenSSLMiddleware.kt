package com.koushikdutta.scratch.http.client.middleware

import com.koushikdutta.scratch.AsyncSocket
import com.koushikdutta.scratch.event.AsyncEventLoop
import com.koushikdutta.scratch.http.client.AsyncHttpClient
import com.koushikdutta.scratch.http.client.AsyncHttpClientSession
import com.koushikdutta.scratch.tls.*

open class OpenSSLMiddleware(eventLoop: AsyncEventLoop, context: SSLContext = getDefaultSSLContext()) : AsyncTlsSocketMiddleware(eventLoop, context) {
    fun install(client: AsyncHttpClient) {
        client.middlewares.add(0, this)
    }

    protected open suspend fun wrapTlsSocket(
        session: AsyncHttpClientSession,
        tlsSocket: AsyncTlsSocket,
        host: String,
        port: Int
    ): AsyncSocket {
        if (tlsSocket.engine !is SSLEngineImpl)
            return tlsSocket
        if ("h2" == tlsSocket.engine.getNegotiatedAlpnProtocol())
            return manageHttp2Connection(session, host, port, tlsSocket)
        return tlsSocket
    }

    override fun configureEngine(engine: SSLEngine) {
        if (engine !is SSLEngineImpl)
            return
        engine.setAlpnProtocols(listOf("h2", "http/1.1"))
    }

    override suspend fun wrapSocket(
        session: AsyncHttpClientSession,
        socket: AsyncSocket,
        host: String,
        port: Int
    ): AsyncSocket {
        return wrapTlsSocket(session, super.wrapSocket(session, socket, host, port) as AsyncTlsSocket, host, port)
    }
}