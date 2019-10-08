package com.koushikdutta.scratch.http.client.middleware

import com.koushikdutta.scratch.AsyncSocket
import com.koushikdutta.scratch.AsyncTlsSocket
import com.koushikdutta.scratch.http.client.AsyncHttpClient
import com.koushikdutta.scratch.http.client.AsyncHttpClientSession
import org.conscrypt.Conscrypt
import java.security.Security
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine

// todo: move this into conscrypt specific library
class ConscryptMiddleware : AsyncTlsSocketMiddleware(getConscryptSSLContext()) {
    private val protocols = arrayOf("http/1.1", "h2")

    fun install(client: AsyncHttpClient) {
        client.middlewares.add(0, this)
    }

    override fun configureEngine(engine: SSLEngine) {
        super.configureEngine(engine)

        Conscrypt.setApplicationProtocols(engine, protocols)
    }

    override suspend fun connectInternal(session: AsyncHttpClientSession, socket: AsyncSocket, host: String, port: Int): AsyncSocket {
        val tlsSocket = super.connectInternal(session, socket, host, port) as AsyncTlsSocket
        if ("h2" == Conscrypt.getApplicationProtocol(tlsSocket.engine))
            return manageHttp2Connection(session, host, port, tlsSocket)
        return tlsSocket
    }

    companion object {
        fun getConscryptSSLContext(): SSLContext {
            return SSLContext.getInstance("Default", Security.getProvider("Conscrypt"))
        }
    }
}