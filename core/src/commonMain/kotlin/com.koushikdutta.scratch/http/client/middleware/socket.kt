package com.koushikdutta.scratch.http.client.middleware

import com.koushikdutta.scratch.AsyncReader
import com.koushikdutta.scratch.AsyncSocket
import com.koushikdutta.scratch.Promise
import com.koushikdutta.scratch.acceptAsync
import com.koushikdutta.scratch.async.startSafeCoroutine
import com.koushikdutta.scratch.collections.Multimap
import com.koushikdutta.scratch.collections.add
import com.koushikdutta.scratch.collections.pop
import com.koushikdutta.scratch.collections.removeValue
import com.koushikdutta.scratch.event.*
import com.koushikdutta.scratch.event.nanoTime
import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.AsyncHttpResponse
import com.koushikdutta.scratch.http.Headers
import com.koushikdutta.scratch.http.client.AsyncHttpClientSession
import com.koushikdutta.scratch.http.client.AsyncHttpClientTransport
import com.koushikdutta.scratch.http.http2.Http2Connection
import com.koushikdutta.scratch.http.http2.Http2ConnectionMode
import com.koushikdutta.scratch.http.http2.Http2Socket
import com.koushikdutta.scratch.http.http2.connect
import com.koushikdutta.scratch.http.http2.okhttp.Protocol

private typealias IOException = Exception

/**
 * Manages socket connection initiation and keep alive.
 */
open class AsyncSocketMiddleware(val eventLoop: AsyncEventLoop) : AsyncHttpClientMiddleware() {
    protected open val scheme = setOf("http", "ws")
    open val defaultPort = 80

    private data class KeepAliveSocket(val socket: AsyncSocket, val socketReader: AsyncReader, val time: Long = nanoTime(), var observe: Boolean = true)
    private val sockets: Multimap<String, KeepAliveSocket> = mutableMapOf()

    private fun observeKeepaliveSocket(socketKey: String, keepAliveSocket: KeepAliveSocket) {
        sockets.add(socketKey, keepAliveSocket)
        startSafeCoroutine {
            try {
                eventLoop.sleep(5000)
                if (keepAliveSocket.observe) {
                    keepAliveSocket.socket.close()
                    sockets.removeValue(socketKey, keepAliveSocket)
                }
            }
            catch (exception: Exception) {
                sockets.removeValue(socketKey, keepAliveSocket)
            }
        }
    }

    override suspend fun onResponseComplete(session: AsyncHttpClientSession) {
        if (session.transport?.owner != this)
            return

        // recycle keep alive sockets if possible.
        // requires connection: keep alive or http/1.1 implicit keep alive.
        // make sure we're using http/1.0 or http/1.1 (and not http 2)
        val protocol = session.transport?.protocol
        if (session.socketKey == null
                || !isKeepAlive(session.request, session.response!!)
                || (protocol != Protocol.HTTP_1_0.toString() && protocol != Protocol.HTTP_1_1.toString())) {
            session.transport!!.socket.close()
            return
        }

        observeKeepaliveSocket(session.socketKey!!, KeepAliveSocket(session.transport!!.socket, session.transport!!.reader))
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
        return socket
    }

    protected open suspend fun resolve(host: String): Array<InetAddress> {
        return eventLoop.getAllByName(host)
    }

    protected open suspend fun connectInternal(session: AsyncHttpClientSession, host: String, port: Int): AsyncSocket {
        val addresses = resolve(host)
        if (addresses.isEmpty())
            throw IOException("host not found (no results)")

        // prefer ipv4
        addresses.sortBy {
            if (it is Inet4Address)
                4
            else
                6
        }

        var throwable: Throwable? = null
        for (address in addresses) {
            try {
                return eventLoop.connect(InetSocketAddress(address, port))
            }
            catch (t: Throwable) {
                if (throwable == null)
                    throwable = t
            }
        }
        throw throwable!!
    }

    override suspend fun connectSocket(session: AsyncHttpClientSession): Boolean {
        if (!scheme.contains(session.request.uri.scheme?.toLowerCase()))
            return false

        val host = session.request.uri.host!!
        val port = if (session.request.uri.port == -1) defaultPort else session.request.uri.port

        val socketKey = "$host:$port"
        session.socketKey = socketKey

        val keepaliveSocket = sockets.pop(socketKey)
        if (keepaliveSocket != null) {
            session.transport = AsyncHttpClientTransport(keepaliveSocket.socket, keepaliveSocket.socketReader)

            // socket has been transfered over to the new session, interrupt the read
            // watcher so it can be safely read.
            keepaliveSocket.observe = false
            return true
        }

        // don't allow http2 connections to be upgraded. websocket does support http2, but it needs to be
        // implemented.
        val connectionUpgrade = "upgrade".equals(session.request.headers["connection"], true)

        if (http2Connections.contains(socketKey) && !connectionUpgrade) {
            connectHttp2(session, http2Connections[socketKey]!!)
            return true
        }

        // todo: connect all IPs
        session.transport = createTransport(session, host, port)

        return true
    }

    protected open suspend fun createTransport(session: AsyncHttpClientSession, host: String, port: Int): AsyncHttpClientTransport {
        return AsyncHttpClientTransport(wrapSocket(session, connectInternal(session, host, port), host, port))
    }

    private val http2Connections = mutableMapOf<String, Http2Connection>()
    val openHttp2Connections
        get(): Int = http2Connections.size

    private suspend fun connectHttp2(session: AsyncHttpClientSession, connection: Http2Connection): Http2Socket {
        val responseSocket = connection.connect(session.request)
        session.transport = AsyncHttpClientTransport(responseSocket, protocol = Protocol.HTTP_2.toString())
        return responseSocket
    }

    internal open suspend fun manageHttp2Connection(session: AsyncHttpClientSession, host: String, port: Int, socket: AsyncSocket): Http2Socket {
        val http2Connection = Http2Connection.upgradeHttp2Connection(socket, Http2ConnectionMode.Client)

        val socketKey = "$host:$port"
        http2Connections[socketKey] = http2Connection

        Promise {
            http2Connection
            .acceptAsync {
                // incoming connections are ignored.
                close()
            }
            .awaitClose()
        }
        .finally {
            http2Connections.remove(socketKey)
        }

        return connectHttp2(session, http2Connection)
    }
}
