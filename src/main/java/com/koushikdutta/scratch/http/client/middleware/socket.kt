package com.koushikdutta.scratch.http.client.middleware

import com.koushikdutta.scratch.AsyncSocket
import com.koushikdutta.scratch.http.client.AsyncHttpClientSession
import com.koushikdutta.scratch.http.http2.Http2Connection
import com.koushikdutta.scratch.http.http2.Http2Stream
import com.koushikdutta.scratch.tlsHandshake
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine


open class AsyncSocketMiddleware : AsyncHttpClientMiddleware() {
    protected open val scheme = "http"
    open val defaultPort = 80

    protected open suspend fun connectInternal(session: AsyncHttpClientSession, socket: AsyncSocket, host: String, port: Int): AsyncSocket {
        session.protocol = "http/1.1"
        return socket
    }

    override suspend fun connectSocket(session: AsyncHttpClientSession): Boolean {
        if (!session.request.uri.scheme.equals(scheme, true))
            return false

        val host = session.request.uri.host
        val port = if (session.request.uri.port == -1) defaultPort else session.request.uri.port

        val h2Key = "$host:$port"
        if (http2Connections.contains(h2Key)) {
            connectHttp2(session, http2Connections[h2Key]!!)
            return true
        }

        // todo: connect all IPs
        session.socket = connectInternal(session, session.networkContext.connect(host, port), host, port)

        return true
    }

    val http2Connections = mutableMapOf<String, Http2Connection>()
    private suspend fun connectHttp2(session: AsyncHttpClientSession, connection: Http2Connection): Http2Stream {
        session.protocol = "h2"
        val responseSocket = connection.newStream(session.request, true)
        session.socket = responseSocket
        println("http2 stream id ${responseSocket.streamId}")
        return responseSocket
    }

    open suspend fun manageHttp2Connection(session: AsyncHttpClientSession, host: String, port: Int, socket: AsyncSocket): AsyncSocket {
        val http2Connection = Http2Connection(socket, true)

        val h2Key = "$host:$port"
        http2Connections[h2Key] = http2Connection

        http2Connection.closed {
            println("http2 connection closed")
            http2Connections.remove(h2Key)
        }

        return connectHttp2(session, http2Connection)
    }
}

open class AsyncTlsSocketMiddleware(val context: SSLContext = SSLContext.getDefault()) : AsyncSocketMiddleware() {
    override val scheme: String = "https"
    override val defaultPort = 443

    protected open fun configureEngine(engine: SSLEngine) {
    }

    override suspend fun connectInternal(session: AsyncHttpClientSession, socket: AsyncSocket, host: String, port: Int): AsyncSocket {
        try {
            val engine = context.createSSLEngine(host, port)
            engine.useClientMode = true
            configureEngine(engine)
            return tlsHandshake(socket, host, engine)
        }
        catch (throwable: Throwable) {
            socket.close()
            throw throwable
        }
    }
}
