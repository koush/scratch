package com.koushikdutta.scratch.http.client

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.buffers.WritableBuffers
import com.koushikdutta.scratch.event.AsyncEventLoop
import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.AsyncHttpResponse
import com.koushikdutta.scratch.http.GET
import com.koushikdutta.scratch.http.POST
import com.koushikdutta.scratch.http.client.middleware.*
import com.koushikdutta.scratch.uri.URI


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

data class AsyncHttpClientSession constructor(val client: AsyncHttpClient, val request: AsyncHttpRequest) {
    var socket: AsyncSocket? = null
    var interrupt: InterruptibleRead? = null
    var socketReader: AsyncReader? = null
    var socketOwner: AsyncHttpClientMiddleware? = null

    var response: AsyncHttpResponse? = null
    var protocol: String? = null
    var socketKey: String? = null
    val properties: AsyncHttpClientSessionProperties = mutableMapOf()
}

class AsyncHttpClientException : Exception {
    constructor(message: String) : super(message)
    constructor(message: String, exception: Exception) : super(message, exception)
}

class AsyncHttpClient(val eventLoop: AsyncEventLoop = AsyncEventLoop()) {
    val middlewares = mutableListOf<AsyncHttpClientMiddleware>()

    init {
        addPlatformMiddleware(this)
        middlewares.add(DefaultsMiddleware())
        middlewares.add(AsyncSocketMiddleware(eventLoop))
        middlewares.add(AsyncTlsSocketMiddleware(eventLoop))
        middlewares.add(AsyncHttpTransportMiddleware())
        middlewares.add(AsyncHttp2TransportMiddleware())
        middlewares.add(AsyncBodyDecoder())
        middlewares.add(AsyncHttpRedirector())
    }

    private suspend fun execute(session: AsyncHttpClientSession) : AsyncHttpResponse {
        var sent = false
        try {
            for (middleware in middlewares) {
                middleware.prepare(session)
            }

            if (session.socket == null) {
                for (middleware in middlewares) {
                    if (middleware.connectSocket(session)) {
                        session.socketOwner = middleware
                        break
                    }
                }
            }

            requireNotNull(session.socket) { "unable to find transport for uri ${session.request.uri}" }

            for (middleware in middlewares) {
                if (middleware.exchangeMessages(session))
                    break
            }
            sent = true
            session.request.sent?.invoke(null)


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
        catch (throwable: Throwable) {
            if (!sent)
                session.request.sent?.invoke(throwable)
            session.socket?.close()
            throw throwable
        }
    }

    suspend fun execute(request: AsyncHttpRequest): AsyncHttpResponse {
        val session = AsyncHttpClientSession(this, request)
        return execute(session)
    }

    suspend fun execute(request: AsyncHttpRequest, socket: AsyncSocket, socketReader: AsyncReader): AsyncHttpResponse {
        val session = AsyncHttpClientSession(this, request)
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

suspend fun AsyncHttpClient.execute(method: String, uri: String): AsyncHttpResponse {
    return execute(AsyncHttpRequest(URI.create(uri), method))
}

suspend fun AsyncHttpClient.get(uri: String): AsyncHttpResponse {
    return execute(AsyncHttpRequest.GET(uri))
}

suspend fun AsyncHttpClient.post(uri: String): AsyncHttpResponse {
    return execute(AsyncHttpRequest.POST(uri))
}

//suspend fun AsyncHttpClient.randomAccess(uri: String): AsyncRandomAccessInput {
//    val headResponse = execute("HEAD", uri)
//    headResponse.
//
//    var closed = false
//
//    return object : AsyncRandomAccessInput, AsyncAffinity by eventLoop {
//        override suspend fun size(): Long {
//        }
//
//        override suspend fun getPosition(): Long {
//        }
//
//        override suspend fun setPosition(position: Long) {
//        }
//
//        override suspend fun readPosition(position: Long, length: Long, buffer: WritableBuffers): Boolean {
//        }
//
//        override suspend fun read(buffer: WritableBuffers): Boolean {
//        }
//
//        override suspend fun close() {
//        }
//    }
//}
