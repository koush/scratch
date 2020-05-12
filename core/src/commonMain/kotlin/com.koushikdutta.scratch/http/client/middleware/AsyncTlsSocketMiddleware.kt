package com.koushikdutta.scratch.http.client.middleware

import com.koushikdutta.scratch.AsyncSocket
import com.koushikdutta.scratch.event.AsyncEventLoop
import com.koushikdutta.scratch.http.client.AsyncHttpClientSession
import com.koushikdutta.scratch.http.client.AsyncHttpClientSocket
import com.koushikdutta.scratch.tls.*

open class AsyncTlsSocketMiddleware(eventLoop: AsyncEventLoop, val context: SSLContext = getDefaultSSLContext()) : AsyncSocketMiddleware(eventLoop) {
    override val scheme = setOf("https", "wss")
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
