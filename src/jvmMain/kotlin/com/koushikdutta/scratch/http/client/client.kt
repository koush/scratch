package com.koushikdutta.scratch.http.client

import com.koushikdutta.scratch.AsyncReader
import com.koushikdutta.scratch.AsyncSocket
import com.koushikdutta.scratch.InterruptibleRead
import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.AsyncHttpResponse
import com.koushikdutta.scratch.http.GET
import com.koushikdutta.scratch.http.client.middleware.*
import com.koushikdutta.scratch.net.AsyncNetworkContext


enum class AsyncHttpRequestMethods {
    HEAD,
    GET,
    POST
}

typealias AsyncHttpClientSessionProperties = MutableMap<String, Any>

var AsyncHttpClientSessionProperties.manageSocket: Boolean
    get() = getOrElse("manage-socket", { true }) as Boolean
    set(value) {
        set("manage-socket", value)
    }

data class AsyncHttpClientSession constructor(val client: AsyncHttpClient, val networkContext: AsyncNetworkContext, val request: AsyncHttpRequest) {
    var socket: AsyncSocket? = null
    var interrupt: InterruptibleRead? = null
    var socketReader: AsyncReader? = null

    var response: AsyncHttpResponse? = null
    var protocol: String? = null
    var socketKey: String? = null
    val properties: AsyncHttpClientSessionProperties = mutableMapOf()
}

class AsyncHttpClientException : Exception {
    constructor(message: String) : super(message)
    constructor(message: String, exception: Exception) : super(message, exception)
}

class AsyncHttpClient(val networkContext: AsyncNetworkContext = AsyncNetworkContext.default) {
    val middlewares = mutableListOf<AsyncHttpClientMiddleware>()
    init {
        middlewares.add(DefaultsMiddleware())
        middlewares.add(AsyncSocketMiddleware())
        middlewares.add(AsyncTlsSocketMiddleware())
        middlewares.add(AsyncHttpTransportMiddleware())
        middlewares.add(AsyncHttp2TransportMiddleware())
        middlewares.add(AsyncBodyDecoder())
        middlewares.add(AsyncHttpRedirector())
    }

    private suspend fun execute(session: AsyncHttpClientSession) : AsyncHttpResponse {
        for (middleware in middlewares) {
            middleware.prepare(session)
        }

        if (session.socket == null) {
            for (middleware in middlewares) {
                if (middleware.connectSocket(session))
                    break
            }
        }

        requireNotNull(session.socket) { "unable to find transport for uri ${session.request.uri}" }

        try {
            for (middleware in middlewares) {
                if (middleware.exchangeMessages(session))
                    break
            }

            if (session.response == null)
                throw AsyncHttpClientException("unable to find transport to exchange headers for uri ${session.request.uri}")

            for (middleware in middlewares) {
                middleware.onResponseStarted(session)
            }

            for (middleware in middlewares) {
                middleware.onBodyReady(session)
            }

            return session.response!!
        }
        catch (exception: Exception) {
            session.socket!!.close()
            throw exception
        }
    }

    suspend fun execute(uri: String): AsyncHttpResponse {
        return execute(AsyncHttpRequest.GET(uri))
    }

    suspend fun execute(request: AsyncHttpRequest): AsyncHttpResponse {
        val session = AsyncHttpClientSession(this, networkContext, request)
        return execute(session)
    }

    suspend fun execute(request: AsyncHttpRequest, socket: AsyncSocket, socketReader: AsyncReader): AsyncHttpResponse {
        val session = AsyncHttpClientSession(this, networkContext, request)
        session.socket = socket
        session.socketReader = socketReader
        session.properties.manageSocket = false
        session.protocol = session.request.protocol.toLowerCase()
        return execute(session)
    }

    internal suspend fun onResponseComplete(session: AsyncHttpClientSession) {
        // there's nothing to report cleanup errors to. what do.
        for (middleware in middlewares) {
            middleware.onResponseComplete(session)
        }
    }
}