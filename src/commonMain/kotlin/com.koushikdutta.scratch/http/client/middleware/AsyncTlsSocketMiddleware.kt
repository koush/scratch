package com.koushikdutta.scratch.http.client.middleware

import com.koushikdutta.scratch.AsyncSocket
import com.koushikdutta.scratch.http.client.AsyncHttpClientSession
import com.koushikdutta.scratch.tls.*

open class AsyncTlsSocketMiddleware(val context: SSLContext = getDefaultSSLContext()) : AsyncSocketMiddleware() {
    override val scheme: String = "https"
    override val defaultPort = 443

    protected open fun configureEngine(engine: SSLEngine) {
    }

    override suspend fun wrapSocket(session: AsyncHttpClientSession, socket: AsyncSocket, host: String, port: Int): AsyncSocket {
        try {
            session.protocol = session.request.protocol.toLowerCase()
            val engine = context.createSSLEngine(host, port)
            engine.useClientMode = true
            configureEngine(engine)
            return tlsHandshake(socket, engine)
        }
        catch (throwable: Throwable) {
            socket.close()
            throw throwable
        }
    }
}
