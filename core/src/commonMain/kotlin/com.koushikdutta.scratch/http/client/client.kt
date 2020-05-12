package com.koushikdutta.scratch.http.client

import AsyncHttpExecutor
import com.koushikdutta.scratch.AsyncReader
import com.koushikdutta.scratch.AsyncSocket
import com.koushikdutta.scratch.IOException
import com.koushikdutta.scratch.InterruptibleRead
import com.koushikdutta.scratch.event.AsyncEventLoop
import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.AsyncHttpResponse
import com.koushikdutta.scratch.http.Headers
import com.koushikdutta.scratch.http.StatusCode
import com.koushikdutta.scratch.http.client.middleware.*

typealias AsyncHttpResponseHandler<R> = suspend (response: AsyncHttpResponse) -> R

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

class AsyncHttpClientSession constructor(val executor: AsyncHttpExecutor, val client: AsyncHttpClient, val request: AsyncHttpRequest) {
    var socket: AsyncSocket? = null
    var interrupt: InterruptibleRead? = null
    var socketReader: AsyncReader? = null
    var socketOwner: AsyncHttpClientMiddleware? = null

    var response: AsyncHttpResponse? = null
    var responseCompleted = false
    var protocol: String? = null
    var socketKey: String? = null
    val properties: AsyncHttpClientSessionProperties = mutableMapOf()
}

class AsyncHttpClientException : Exception {
    constructor(message: String) : super(message)
    constructor(message: String, exception: Exception) : super(message, exception)
}

interface AsyncHttpDetachedSocket {
    val socket: AsyncSocket
    val socketReader: AsyncReader
}

class AsyncHttpClientSwitchingProtocols(val responseHeaders: Headers, override val socket: AsyncSocket, override val socketReader: AsyncReader): Exception(), AsyncHttpDetachedSocket

class AsyncHttpClient(override val eventLoop: AsyncEventLoop = AsyncEventLoop()): AsyncHttpExecutor {
    val middlewares = mutableListOf<AsyncHttpClientMiddleware>()
    override val client = this

    init {
        addPlatformMiddleware(this)
        middlewares.add(DefaultsMiddleware())
        middlewares.add(AsyncSocketMiddleware(eventLoop))
        middlewares.add(AsyncTlsSocketMiddleware(eventLoop))
        middlewares.add(AsyncHttpTransportMiddleware())
        middlewares.add(AsyncHttp2TransportMiddleware())
        middlewares.add(AsyncBodyDecoder())
    }

    override suspend fun execute(session: AsyncHttpClientSession): AsyncHttpResponse {
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

            if (session.response!!.code == StatusCode.SWITCHING_PROTOCOLS.code) {
                // if the request was expecting an upgrade, throw a special exception with the socket and socket reader,
                // and completely bail on this request.
                if (session.request.headers["Connection"]?.equals("Upgrade", true) == true && session.request.headers["Upgrade"] != null)
                    throw AsyncHttpClientSwitchingProtocols(session.response!!.headers, session.socket!!, session.socketReader!!)
                throw IOException("Received unexpected connection upgrade")
            }

            for (middleware in middlewares) {
                middleware.onResponseStarted(session)
            }

            for (middleware in middlewares) {
                middleware.onBodyReady(session)
            }

            return session.response!!
        }
        catch (switching: AsyncHttpClientSwitchingProtocols) {
            throw switching
        }
        catch (throwable: Throwable) {
            if (!sent)
                session.request.sent?.invoke(throwable)
            session.socket?.close()
            throw throwable
        }
    }

    internal suspend fun onResponseComplete(session: AsyncHttpClientSession) {
        session.responseCompleted = true
        // there's nothing to report cleanup errors to. what do.
        for (middleware in middlewares) {
            middleware.onResponseComplete(session)
        }
    }
}
