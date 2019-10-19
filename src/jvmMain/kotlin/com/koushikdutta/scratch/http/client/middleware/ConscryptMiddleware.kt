package com.koushikdutta.scratch.http.client.middleware

import com.koushikdutta.scratch.AsyncSocket
import com.koushikdutta.scratch.tls.AsyncTlsSocket
import com.koushikdutta.scratch.http.client.AsyncHttpClient
import com.koushikdutta.scratch.http.client.AsyncHttpClientSession
import org.conscrypt.Conscrypt
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine

// todo: move this into conscrypt specific library
open class ConscryptMiddleware(context: SSLContext = getConscryptSSLContext()) : AsyncTlsSocketMiddleware(context) {
    private val protocols = arrayOf("h2", "http/1.1")

    fun install(client: AsyncHttpClient) {
        client.middlewares.add(0, this)
    }

    override fun configureEngine(engine: SSLEngine) {
        super.configureEngine(engine)

        Conscrypt.setApplicationProtocols(engine, protocols)
    }

    protected open suspend fun wrapTlsSocket(session: AsyncHttpClientSession, tlsSocket: AsyncTlsSocket, host: String, port: Int): AsyncSocket {
        if ("h2" == Conscrypt.getApplicationProtocol(tlsSocket.engine))
            return manageHttp2Connection(session, host, port, tlsSocket)
        return tlsSocket
    }

    override suspend fun wrapSocket(session: AsyncHttpClientSession, socket: AsyncSocket, host: String, port: Int): AsyncSocket {
        return wrapTlsSocket(session, super.wrapSocket(session, socket, host, port) as AsyncTlsSocket, host, port)
    }

    companion object {
        fun getConscryptSSLContext(): SSLContext {
            return SSLContext.getInstance("Default", Conscrypt.newProvider())
        }
    }
}