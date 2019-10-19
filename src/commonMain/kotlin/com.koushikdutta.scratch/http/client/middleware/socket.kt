package com.koushikdutta.scratch.http.client.middleware

import com.koushikdutta.scratch.AsyncReader
import com.koushikdutta.scratch.AsyncSocket
import com.koushikdutta.scratch.InterruptibleRead
import com.koushikdutta.scratch.async
import com.koushikdutta.scratch.collections.Multimap
import com.koushikdutta.scratch.collections.add
import com.koushikdutta.scratch.collections.pop
import com.koushikdutta.scratch.collections.removeValue
import com.koushikdutta.scratch.event.connect
import com.koushikdutta.scratch.event.nanoTime
import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.AsyncHttpResponse
import com.koushikdutta.scratch.http.Headers
import com.koushikdutta.scratch.http.client.AsyncHttpClientSession
import com.koushikdutta.scratch.http.client.manageSocket
import com.koushikdutta.scratch.http.http2.Http2Connection
import com.koushikdutta.scratch.http.http2.Http2Stream
import com.koushikdutta.scratch.http.http2.okhttp.Protocol

private typealias IOException = Exception

/**
 * Manages socket connection initiation and keep alive.
 */
open class AsyncSocketMiddleware : AsyncHttpClientMiddleware() {
    protected open val scheme = "http"
    open val defaultPort = 80

    private data class KeepAliveSocket(val socket: AsyncSocket, val interrupt: InterruptibleRead, val socketReader: AsyncReader, val time: Long = nanoTime(), var observe: Boolean = true)
    private val sockets: Multimap<String, KeepAliveSocket> = mutableMapOf()

    private fun observeKeepaliveSocket(socketKey: String, keepAliveSocket: KeepAliveSocket) {
        async {
            keepAliveSocket.socket.await()
            try {
                while (keepAliveSocket.observe) {
                    if (keepAliveSocket.socketReader.buffered > 0)
                        throw IOException("keep alive socket received unexpected data")
                    if (!keepAliveSocket.socketReader.readBuffer())
                        throw IOException("keep alive socket closed")
                }
            }
            catch (exception: Exception) {
                sockets.removeValue(socketKey, keepAliveSocket)
            }
        }
        sockets.add(socketKey, keepAliveSocket)
    }

    override suspend fun onResponseComplete(session: AsyncHttpClientSession) {
        if (session.socketOwner != this)
            return

        // recycle keep alive sockets if possible.
        // requires connection: keep alive or http/1.1 implicit keep alive.
        // make sure we're using http/1.0 or http/1.1 (and not http 2)
        if (session.socketKey == null
                || !isKeepAlive(session.request, session.response!!)
                || (session.protocol != Protocol.HTTP_1_0.toString() && session.protocol != Protocol.HTTP_1_1.toString())) {
            if (session.properties.manageSocket)
                session.socket!!.close()
            return
        }

        observeKeepaliveSocket(session.socketKey!!, KeepAliveSocket(session.socket!!, session.interrupt!!, session.socketReader!!))
    }

    companion object {
        fun isKeepAlive(request: AsyncHttpRequest, response: AsyncHttpResponse): Boolean {
            return isKeepAlive(response.protocol, response.headers) && isKeepAlive(request.protocol, request.headers)
        }

        fun isKeepAlive(protocol: String, headers: Headers): Boolean {
            // connection is always keep alive as this is an http/1.1 client
            val connection = headers.get("Connection") ?: return protocol.toLowerCase() == Protocol.HTTP_1_1.toString()
            return "keep-alive".equals(connection, ignoreCase = true)
        }
    }

    protected open suspend fun wrapSocket(session: AsyncHttpClientSession, socket: AsyncSocket, host: String, port: Int): AsyncSocket {
        session.protocol = session.request.protocol.toLowerCase()
        return socket
    }

    protected open suspend fun connectInternal(session: AsyncHttpClientSession, host: String, port: Int): AsyncSocket {
        return session.eventLoop.connect(host, port)
    }

    fun ensureSocketReader(session: AsyncHttpClientSession) {
        session.interrupt = InterruptibleRead({session.socket!!.read(it)})
        session.socketReader = AsyncReader({session.interrupt!!.read(it)})
    }

    override suspend fun connectSocket(session: AsyncHttpClientSession): Boolean {
        if (!session.request.uri.scheme.equals(scheme, true))
            return false


        val host = session.request.uri.host!!
        val port = if (session.request.uri.port == -1) defaultPort else session.request.uri.port

        val socketKey = "$host:$port"
        session.socketKey = socketKey

        val keepaliveSocket = sockets.pop(socketKey)
        if (keepaliveSocket != null) {
            session.protocol = session.request.protocol.toLowerCase()
            session.socket = keepaliveSocket.socket
            session.interrupt = keepaliveSocket.interrupt
            session.socketReader = keepaliveSocket.socketReader

            // socket has been transfered over to the new session, interrupt the read
            // watcher so it can be safely read.
            keepaliveSocket.observe = false
            session.interrupt!!.interrupt()
            return true
        }

        if (http2Connections.contains(socketKey)) {
            connectHttp2(session, http2Connections[socketKey]!!)
            return true
        }

        // todo: connect all IPs
        session.socket = wrapSocket(session, connectInternal(session, host, port), host, port)
        ensureSocketReader(session)

        return true
    }

    val http2Connections = mutableMapOf<String, Http2Connection>()
    private suspend fun connectHttp2(session: AsyncHttpClientSession, connection: Http2Connection): Http2Stream {
        session.protocol = Protocol.HTTP_2.toString()
        val responseSocket = connection.newStream(session.request)
        session.socket = responseSocket
        ensureSocketReader(session)
        return responseSocket
    }

    open suspend fun manageHttp2Connection(session: AsyncHttpClientSession, host: String, port: Int, socket: AsyncSocket): AsyncSocket {
        val http2Connection = Http2Connection(socket, true)

        val socketKey = "$host:$port"
        http2Connections[socketKey] = http2Connection

        http2Connection.closed {
            http2Connections.remove(socketKey)
        }

        return connectHttp2(session, http2Connection)
    }
}
