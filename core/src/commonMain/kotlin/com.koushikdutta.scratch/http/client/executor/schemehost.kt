package com.koushikdutta.scratch.http.client.executor

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.event.*
import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.client.AsyncHttpExecutor
import com.koushikdutta.scratch.http.http2.okhttp.Protocol
import com.koushikdutta.scratch.tls.*
import com.koushikdutta.scratch.uri.URI

class SchemeUnhandledException(uri: URI) : IOException("unable to find scheme handler for ${uri}")

class SchemeExecutor(override val affinity: AsyncAffinity) : RegisterKeyPoolExecutor() {
    override var unhandled: AsyncHttpExecutor = {
        throw SchemeUnhandledException(it.uri)
    }

    override fun getKey(request: AsyncHttpRequest): String {
        if (request.uri.scheme == null)
            return ""
        return request.uri.scheme!!
    }

    fun register(scheme: String, executor: AsyncHttpExecutor): SchemeExecutor {
        pool[scheme] = executor
        return this
    }
}

class HostExecutor(override val affinity: AsyncAffinity = AsyncAffinity.NO_AFFINITY, create: KeyPoolExecutorFactory): CreateKeyPoolExecutor(create) {
    override fun getKey(request: AsyncHttpRequest): String {
        return request.uri.host!! + ":" + request.uri.port
    }
}


fun createHostExecutor(affinity: AsyncAffinity, defaultPort: Int, connect: HostPortResolver<AsyncSocket>) = HostExecutor(affinity) {
    val port = if (it.uri.port == -1)
        defaultPort
    else
        it.uri.port

    val connectExecutor = AsyncHttpConnectSocketExecutor(affinity) {
        connect(it.uri.host!!, port)
    }
    connectExecutor::invoke
}

fun createHostAlpnExecutor(affinity: AsyncAffinity, defaultPort: Int, connect: HostPortResolver<AlpnSocket>) = HostExecutor(affinity) {
    val port = if (it.uri.port == -1)
        defaultPort
    else
        it.uri.port

    val connectExecutor = AsyncHttpAlpnExecutor(affinity) {
        connect(it.uri.host!!, port)
    }
    connectExecutor::invoke
}

private typealias WrapConnect<F, T> = suspend F.(host: String, port: Int) -> T
typealias HostPortResolver<T> = suspend (host: String, port: Int) -> T

typealias HostSocketProvider = suspend () -> AsyncSocket
typealias HostCandidatesProvider = suspend (host: String, port: Int) -> AsyncIterator<HostSocketProvider>

fun createDefaultResolver(eventLoop: AsyncEventLoop): HostCandidatesProvider = {
    host, port ->
    asyncIterator {
        val resolved = eventLoop.getAllByName(host)
        for (address in resolved) {
            val connect: HostSocketProvider = {
                eventLoop.connect(InetSocketAddress(address, port))
            }
            yield(connect)
        }
    }
}

fun <T: AsyncSocket> connectFirstAvailableResolver(connectionProvider: HostCandidatesProvider, wrapConnect: WrapConnect<AsyncSocket, T>):
        HostPortResolver<T> = first@{ host: String, port: Int ->
    val candidates = connectionProvider(host, port)
    var throwable: Throwable? = null
    while (candidates.hasNext()) {
        val candidate = candidates.next()
        try {
            val socket = candidate()
            return@first wrapConnect(socket, host, port)
        }
        catch (t: Throwable) {
            if (throwable == null)
                throwable = t
        }
    }
    throw throwable!!
}

fun connectFirstAvailableResolver(connectionProvider: HostCandidatesProvider) = connectFirstAvailableResolver(connectionProvider) { _, _ ->
    this
}

fun SchemeExecutor.useHttpExecutor(affinity: AsyncAffinity = AsyncAffinity.NO_AFFINITY,
                                    resolver: HostPortResolver<AsyncSocket>): SchemeExecutor {
    val http = createHostExecutor(affinity, 80, resolver)
    register("http", http::invoke)
    register("ws", http::invoke)
    return this
}

fun SchemeExecutor.useHttpsExecutor(affinity: AsyncAffinity = AsyncAffinity.NO_AFFINITY,
                                    resolver: HostPortResolver<AsyncSocket>): SchemeExecutor {
    val https = createHostExecutor(affinity, 443, resolver)

    register("https", https::invoke)
    register("wss", https::invoke)
    return this
}

fun SchemeExecutor.useHttpExecutor(eventLoop: AsyncEventLoop,
                                   resolver: HostPortResolver<AsyncSocket> =
                                           connectFirstAvailableResolver(createDefaultResolver(eventLoop))) =
        useHttpExecutor(eventLoop as AsyncAffinity, resolver)

fun SchemeExecutor.useHttpsExecutor(affinity: AsyncAffinity,
                                    sslContext: SSLContext = getDefaultSSLContext(),
                                    candidates: HostCandidatesProvider) =
        useHttpsExecutor(affinity, connectFirstAvailableResolver(candidates) { host, port ->
            connectTls(host, port, sslContext)
        })

fun SchemeExecutor.useHttpsExecutor(eventLoop: AsyncEventLoop,
                                    sslContext: SSLContext = getDefaultSSLContext(),
                                    candidates: HostCandidatesProvider = createDefaultResolver(eventLoop)) =
        useHttpsExecutor(eventLoop as AsyncAffinity, sslContext, candidates)



fun SchemeExecutor.useHttpsAlpnExecutor(affinity: AsyncAffinity = AsyncAffinity.NO_AFFINITY,
                                    resolver: HostPortResolver<AlpnSocket>): SchemeExecutor {
    val https = createHostAlpnExecutor(affinity, 443, resolver)

    register("https", https::invoke)
    register("wss", https::invoke)
    return this
}


fun SchemeExecutor.useHttpsAlpnExecutor(affinity: AsyncAffinity,
                                    sslContext: SSLContext = getDefaultALPNSSLContext(),
                                    candidates: HostCandidatesProvider) =
        useHttpsAlpnExecutor(affinity, connectFirstAvailableResolver(candidates) { host, port ->
            val engine = sslContext.createSSLEngine(host, port)
            engine.setApplicationProtocols(Protocol.HTTP_2.protocol, Protocol.HTTP_1_1.protocol)
            val socket = connectTls(engine)
            object : AlpnSocket, AsyncSocket by socket {
                override val negotiatedProtocol = engine.getApplicationProtocol()
            }
        })

fun SchemeExecutor.useHttpsAlpnExecutor(eventLoop: AsyncEventLoop,
                                    sslContext: SSLContext = getDefaultALPNSSLContext(),
                                    candidates: HostCandidatesProvider = createDefaultResolver(eventLoop)) =
        useHttpsAlpnExecutor(eventLoop as AsyncAffinity, sslContext, candidates)
