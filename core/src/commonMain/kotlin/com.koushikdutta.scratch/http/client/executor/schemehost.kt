package com.koushikdutta.scratch.http.client.executor

import com.koushikdutta.scratch.AsyncSocket
import com.koushikdutta.scratch.IOException
import com.koushikdutta.scratch.event.AsyncEventLoop
import com.koushikdutta.scratch.event.Inet4Address
import com.koushikdutta.scratch.event.InetSocketAddress
import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.client.AsyncHttpClient
import com.koushikdutta.scratch.http.client.AsyncHttpExecutor
import com.koushikdutta.scratch.tls.connectTls

class SchemeExecutor(val scheme: String, override val client: AsyncHttpClient) : RegisterKeyPoolExecutor() {
    override fun getKey(request: AsyncHttpRequest): String {
        return request.uri.scheme!!
    }

    fun register(scheme: String, executor: AsyncHttpExecutor): SchemeExecutor {
        pool[scheme] = executor
        return this
    }
}

class HostExecutor(override val client: AsyncHttpClient, create: KeyPoolExecutorFactory): CreateKeyPoolExecutor(create) {
    override fun getKey(request: AsyncHttpRequest): String {
        return request.uri.host!! + ":" + request.uri.port
    }
}

typealias ConnectAuthority = suspend (host: String, port: Int) -> AsyncSocket

fun createHostExecutor(client: AsyncHttpClient, defaultPort: Int, connect: ConnectAuthority) = HostExecutor(client) {
    val port = if (it.uri.port == -1)
        defaultPort
    else
        it.uri.port

    val connectExecutor = AsyncHttpConnectSocketExecutor(client.eventLoop) {
        connect(it.uri.host!!, port)
    }
    connectExecutor::invoke
}

private typealias WrapConnect = suspend (host: String, port: Int, socket: AsyncSocket) -> AsyncSocket

private fun AsyncEventLoop.connectAny(wrapConnect: WrapConnect = { _, _, socket -> socket }): ConnectAuthority {
    return ret@{ host: String, port: Int ->
        val resolved = getAllByName(host)
        if (resolved.isEmpty())
            throw IOException("$host resolution failed, no results")
        resolved.sortBy {
            if (it is Inet4Address)
                4
            else
                6
        }
        var throwable: Throwable? = null
        for (address in resolved) {
            try {
                val socket = connect(InetSocketAddress(address, port))
                return@ret wrapConnect(host, port, socket)
            }
            catch (t: Throwable) {
                if (throwable == null)
                    throwable = t
            }
        }

        throw throwable!!
    }
}

fun SchemeExecutor.useHttpScheme(): SchemeExecutor {
    val http = createHostExecutor(client, 80, eventLoop.connectAny())
    register("http", http::execute)
    register("ws", http::execute)
    return this
}

fun SchemeExecutor.useHttpsScheme(): SchemeExecutor {
    val https = createHostExecutor(client, 443, eventLoop.connectAny {  host, port, socket ->
        socket.connectTls(host, port)
    })

    register("https", https::execute)
    register("wss", https::execute)
    return this
}
