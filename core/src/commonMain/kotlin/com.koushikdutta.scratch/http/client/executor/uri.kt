package com.koushikdutta.scratch.http.client.executor

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.buffers.createByteBufferList
import com.koushikdutta.scratch.event.AsyncEventLoop
import com.koushikdutta.scratch.event.InetSocketAddress
import com.koushikdutta.scratch.event.connect
import com.koushikdutta.scratch.http.*
import com.koushikdutta.scratch.http.client.AsyncHttpExecutor
import com.koushikdutta.scratch.http.http2.okhttp.Protocol
import com.koushikdutta.scratch.tls.*

typealias ResolvedSocketConnect<T> = suspend() -> T
typealias RequestSocketResolver = suspend (request:AsyncHttpRequest) -> AsyncIterable<AsyncSocket>

fun AsyncHttpRequest.getPortOrDefault(defaultPort: Int): Int {
    return if (uri.port == -1)
        defaultPort
    else
        uri.port
}

fun createNetworkResolver(defaultPort: Int, eventLoop: AsyncEventLoop): RequestSocketResolver {
    return { request ->
        val port = request.getPortOrDefault(defaultPort)
        val host = request.uri.host!!

        createAsyncIterable {
            val resolved = eventLoop.getAllByName(host)
            for (address in resolved) {
                try {
                    yield(eventLoop.connect(InetSocketAddress(address, port)))
                }
                catch (_: Throwable) {
                    // ignore errors, try all addresses
                }
            }
        }
    }
}

fun AsyncHttpRequest.setProxy(host: String, port: Int) {
    headers["X-Scratch-ProxyHost"] = host
    headers["X-Scratch-ProxyPort"] = port.toString()
}

private val AsyncHttpRequest.isProxying: Boolean
    get() = headers["X-Scratch-ProxyHost"] != null && headers["X-Scratch-ProxyPort"] != null


abstract class HostExecutor<T: AsyncSocket>(override val affinity: AsyncAffinity, val defaultPort: Int, val resolver: RequestSocketResolver): CreateKeyPoolExecutor() {
    override fun getKey(request: AsyncHttpRequest): String {
        return request.uri.host!! + ":" + request.uri.port + ":" + request.isProxying
    }

    abstract suspend fun upgrade(request: AsyncHttpRequest, socket: AsyncSocket): T

    override suspend fun createExecutor(request: AsyncHttpRequest): AsyncHttpExecutor {
        return createConnectExecutor(request) {
            val isProxying = request.isProxying
            val candidates = if (!isProxying) {
                resolver(request)
            }
            else {
                val proxyHost = request.headers["X-Scratch-ProxyHost"]!!
                val proxyPort = request.headers["X-Scratch-ProxyPort"]!!
                request.headers.remove("X-Scratch-ProxyHost")
                request.headers.remove("X-Scratch-ProxyPort")
                val proxyRequest = Methods.CONNECT("proxy://$proxyHost:$proxyPort")
                val candidates = resolver(proxyRequest)
                createAsyncIterable {
                    val port = request.getPortOrDefault(defaultPort)
                    val host = request.uri.host!!

                    for (socket in candidates) {
                        val connectBuffer = "CONNECT ${host}:$port HTTP/1.1\r\n\r\n".createByteBufferList()
                        socket::write.drain(connectBuffer)
                        val reader = AsyncReader(socket::read)
                        val statusLine = reader.readHttpHeaderLine().trim()
                        val headers = reader.readHeaderBlock()
                        val responseLine = ResponseLine(statusLine)
                        if (responseLine.code != 200)
                            throw Exception("proxy failure, code ${responseLine.code}")
                        yield(socket)
                    }
                }
            }
            var throwable: Throwable? = null
            for (socket in candidates) {
                try {
                    return@createConnectExecutor upgrade(request, socket)
                }
                catch (t: Throwable) {
                    if (throwable == null)
                        throwable = t
                }
            }
            throw throwable!!
        }
    }

    abstract suspend fun createConnectExecutor(request: AsyncHttpRequest, connect: ResolvedSocketConnect<T>): AsyncHttpExecutor
}

class HttpHostExecutor(affinity: AsyncAffinity, resolver: RequestSocketResolver): HostExecutor<AsyncSocket>(affinity, 80, resolver) {
    override suspend fun createConnectExecutor(request: AsyncHttpRequest, connect: ResolvedSocketConnect<AsyncSocket>): AsyncHttpExecutor {
        return AsyncHttpConnectSocketExecutor(affinity, connect)::invoke
    }

    override suspend fun upgrade(request: AsyncHttpRequest, socket: AsyncSocket): AsyncSocket {
        return socket
    }

    companion object {
        fun createDefaultHttpHostExecutor(eventLoop: AsyncEventLoop): HttpHostExecutor {
            val resolver = createNetworkResolver(80, eventLoop)
            return HttpHostExecutor(eventLoop, resolver)
        }
    }
}

class HttpsHostExecutor(affinity: AsyncAffinity, val sslContext: SSLContext, resolver: RequestSocketResolver):
        HostExecutor<AsyncTlsSocket>(affinity, 443, resolver) {

    override suspend fun upgrade(request: AsyncHttpRequest, socket: AsyncSocket): AsyncTlsSocket {
        val port = request.getPortOrDefault(443)
        val host = request.uri.host!!
        return socket.connectTls(host, port, sslContext)
    }


    override suspend fun createConnectExecutor(request: AsyncHttpRequest, connect: ResolvedSocketConnect<AsyncTlsSocket>): AsyncHttpExecutor {
        return AsyncHttpConnectSocketExecutor(affinity, connect)::invoke
    }
}

class HttpsAlpnHostExecutor(affinity: AsyncAffinity, val sslContext: SSLContext, resolver: RequestSocketResolver):
        HostExecutor<AlpnSocket>(affinity, 443, resolver) {

    override suspend fun upgrade(request: AsyncHttpRequest, socket: AsyncSocket): AlpnSocket {
        val port = request.getPortOrDefault(443)
        val host = request.uri.host!!
        val engine = sslContext.createSSLEngine(host, port)
        engine.setApplicationProtocols(Protocol.HTTP_2.protocol, Protocol.HTTP_1_1.protocol)
        val tlsSocket = socket.connectTls(engine)
        return object : AlpnSocket, AsyncSocket by tlsSocket {
            override val negotiatedProtocol = engine.getApplicationProtocol()
        }
    }

    override suspend fun createConnectExecutor(request: AsyncHttpRequest, connect: ResolvedSocketConnect<AlpnSocket>): AsyncHttpExecutor {
        return AsyncHttpAlpnExecutor(affinity, connect)::invoke
    }
}

fun SchemeExecutor.useHttpExecutor(affinity: AsyncAffinity,
                                   resolver: RequestSocketResolver): SchemeExecutor {
    val http = HttpHostExecutor(affinity, resolver)
    register("http", http::invoke)
    register("ws", http::invoke)
    return this
}

fun SchemeExecutor.useHttpExecutor(eventLoop: AsyncEventLoop) = this.useHttpExecutor(eventLoop, createNetworkResolver(80, eventLoop))

fun SchemeExecutor.useHttpsExecutor(affinity: AsyncAffinity,
                                    sslContext: SSLContext = getDefaultSSLContext(),
                                    resolver: RequestSocketResolver): SchemeExecutor {
    val https = HttpsHostExecutor(affinity, sslContext, resolver)

    register("https", https::invoke)
    register("wss", https::invoke)
    return this
}

fun SchemeExecutor.useHttpsExecutor(eventLoop: AsyncEventLoop, sslContext: SSLContext = getDefaultSSLContext()) =
        this.useHttpsExecutor(eventLoop, sslContext, createNetworkResolver(443, eventLoop))

fun SchemeExecutor.useHttpsAlpnExecutor(affinity: AsyncAffinity,
                                        sslContext: SSLContext = getDefaultALPNSSLContext(),
                                        resolver: RequestSocketResolver): SchemeExecutor {
    val https = HttpsAlpnHostExecutor(affinity, sslContext, resolver)

    register("https", https::invoke)
    register("wss", https::invoke)
    return this
}

fun SchemeExecutor.useHttpsAlpnExecutor(eventLoop: AsyncEventLoop, sslContext: SSLContext = getDefaultALPNSSLContext()) =
        this.useHttpsAlpnExecutor(eventLoop, sslContext, createNetworkResolver(443, eventLoop))
