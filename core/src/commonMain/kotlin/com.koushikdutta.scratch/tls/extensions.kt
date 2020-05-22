package com.koushikdutta.scratch.tls

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.event.AsyncEventLoop
import com.koushikdutta.scratch.event.connect

suspend fun tlsHandshake(socket: AsyncSocket, engine: SSLEngine, options: AsyncTlsOptions? = null): AsyncTlsSocket {
    val tlsSocket = AsyncTlsSocket(socket, engine, options)
    tlsSocket.awaitHandshake()
    return tlsSocket
}

suspend fun AsyncEventLoop.connectTls(host: String, port: Int, context: SSLContext = getDefaultSSLContext(), options: AsyncTlsOptions? = null): AsyncTlsSocket {
    return connect(host, port).connectTls(host, port, context, options)
}

suspend fun AsyncSocket.connectTls(engine: SSLEngine, options: AsyncTlsOptions? = null): AsyncTlsSocket {
    engine.useClientMode = true
    try {
        return tlsHandshake(this, engine, options)
    }
    catch (exception: Exception) {
        close()
        throw exception
    }
}

suspend fun AsyncSocket.connectTls(host: String, port: Int, context: SSLContext = getDefaultSSLContext(), options: AsyncTlsOptions? = null): AsyncTlsSocket {
    val engine = context.createSSLEngine(host, port)
    return connectTls(engine, options)
}

typealias CreateSSLEngine = () -> SSLEngine
fun AsyncServerSocket<*>.listenTls(createSSLEngine: CreateSSLEngine): AsyncServerSocket<AsyncTlsSocket> {
    val wrapped = this
    return object: AsyncServerSocket<AsyncTlsSocket>, AsyncAffinity by wrapped  {
        override fun accept(): AsyncIterable<out AsyncTlsSocket> {
            val iterator = asyncIterator<AsyncTlsSocket> {
                for (socket in wrapped.accept()) {
                    val engine = createSSLEngine()
                    engine.useClientMode = false
                    val tlsSocket = try {
                        tlsHandshake(socket, engine)
                    }
                    catch (exception: Exception) {
                        println("FAILURESUACUEA")
                        socket.close()
                        continue
                    }

                    yield(tlsSocket)
                }
            }

            return object : AsyncIterable<AsyncTlsSocket> {
                override fun iterator(): AsyncIterator<AsyncTlsSocket> {
                    return iterator
                }
            }
        }

        override suspend fun close() = wrapped.close()

        override suspend fun close(throwable: Throwable) = wrapped.close(throwable)
    }
}

fun AsyncServerSocket<*>.listenTls(context: SSLContext = getDefaultSSLContext()): AsyncServerSocket<AsyncTlsSocket> {
    return this.listenTls {
        context.createSSLEngine()
    }
}

var SSLEngine.useClientMode: Boolean
    get() = getUseClientMode()
    set(value) = setUseClientMode(value)
