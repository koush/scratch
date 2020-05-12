package com.koushikdutta.scratch.http.client

import AsyncHttpExecutor
import com.koushikdutta.scratch.AsyncReader
import com.koushikdutta.scratch.AsyncSocket
import com.koushikdutta.scratch.IOException
import com.koushikdutta.scratch.event.AsyncEventLoop
import com.koushikdutta.scratch.http.*
import com.koushikdutta.scratch.http.client.middleware.*
import com.koushikdutta.scratch.http.http2.okhttp.Protocol

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

// the http transport used to serve an http request and response.
// note that protocol is different from the protocol that is sent on the request line.
// the protocol field here describes the transport protocol used by the socket
// for message exchange and interleaving. ie, http1 or http2.
// http2 transport still sends HTTP/1.1 on the request line.
// rtsp on the request line also uses http/1.1 as the transport.
class AsyncHttpClientTransport(val socket: AsyncSocket, reader: AsyncReader? = null, val owner: AsyncHttpClientMiddleware? = null, val protocol: String = Protocol.HTTP_1_1.toString(), val completion: AsyncHttpMessageCompletion? = null) {
    val reader = reader ?: AsyncReader(socket::read)
}

class AsyncHttpClientSession constructor(val executor: AsyncHttpExecutor, val request: AsyncHttpRequest) {
    var transport: AsyncHttpClientTransport? = null
    var response: AsyncHttpResponse? = null
    var responseCompleted = false
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

            if (session.transport == null) {
                for (middleware in middlewares) {
                    if (middleware.connectSocket(session)) {
//                        session.socketOwner = middleware
                        break
                    }
                }
            }

            requireNotNull(session.transport) { "unable to find transport for uri ${session.request.uri}" }

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
                    throw AsyncHttpClientSwitchingProtocols(session.response!!.headers, session.transport!!.socket, session.transport!!.reader)
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
            session.transport?.socket?.close()
            throw throwable
        }
    }
}
