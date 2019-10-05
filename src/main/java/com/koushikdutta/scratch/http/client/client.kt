package com.koushikdutta.scratch.http.client

import AsyncHttp2TransportMiddleware
import com.koushikdutta.scratch.AsyncReader
import com.koushikdutta.scratch.AsyncSocket
import com.koushikdutta.scratch.async
import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.AsyncHttpResponse
import com.koushikdutta.scratch.http.client.middleware.*
import com.koushikdutta.scratch.net.AsyncNetworkContext


enum class AsyncHttpRequestMethods {
    HEAD,
    GET,
    POST
}

data class AsyncHttpClientSession(val client: AsyncHttpClient, val networkContext: AsyncNetworkContext, val request: AsyncHttpRequest) {
    var response: AsyncHttpResponse? = null
    var socket: AsyncSocket? = null
    var socketReader: AsyncReader? = null
    var protocol: String? = null
}

class AsyncHttpClientException : Exception {
    constructor(message: String) : super(message)
    constructor(message: String, exception: Exception) : super(message, exception)
}


class AsyncHttpClient(val networkContext: AsyncNetworkContext = AsyncNetworkContext.default) {
    val middlewares = mutableListOf<AsyncHttpClientMiddleware>()
    init {
        middlewares.add(AsyncSocketMiddleware())
        middlewares.add(AsyncTlsSocketMiddleware())
        middlewares.add(AsyncHttpTransportMiddleware())
        middlewares.add(AsyncHttp2TransportMiddleware())
        middlewares.add(AsyncBodyDecoder())
        middlewares.add(AsyncHttpRedirector())
    }

    suspend fun execute(request: AsyncHttpRequest): AsyncHttpResponse {
        val session = AsyncHttpClientSession(this, networkContext, request)
        for (middleware in middlewares) {
            if (middleware.connectSocket(session))
                break
        }

        requireNotNull(session.socket) { "unable to find transport for uri ${session.request.uri}" }

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

    internal suspend fun onResponseComplete(session: AsyncHttpClientSession) {
        networkContext.post {
            async {
                // there's nothing to report cleanup errors to. what do.
                for (middleware in middlewares) {
                    middleware.onResponseComplete(session)
                }
            }
        }
    }
}