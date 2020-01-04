package com.koushikdutta.scratch.http.client

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.atomic.FreezableReference
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.WritableBuffers
import com.koushikdutta.scratch.event.AsyncEventLoop
import com.koushikdutta.scratch.http.*
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

class AsyncHttpClientSession constructor(val client: AsyncHttpClient, val request: AsyncHttpRequest) {
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
    }

    private suspend fun execute(session: AsyncHttpClientSession): AsyncHttpResponse {
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

    suspend fun <R> execute(request: AsyncHttpRequest, handler: AsyncHttpResponseHandler<R>): R {
        val session = AsyncHttpClientSession(this, request)
        return execute(session).handle(handler)
    }

    suspend fun <R> execute(request: AsyncHttpRequest, socket: AsyncSocket, socketReader: AsyncReader, handler: AsyncHttpResponseHandler<R>): R {
        val session = AsyncHttpClientSession(this, request)
        session.socket = socket
        session.socketReader = socketReader
        session.properties.manageSocket = false
        session.protocol = session.request.protocol.toLowerCase()
        return execute(session).handle(handler)
    }

    internal suspend fun onResponseComplete(session: AsyncHttpClientSession) {
        session.responseCompleted = true
        // there's nothing to report cleanup errors to. what do.
        for (middleware in middlewares) {
            middleware.onResponseComplete(session)
        }
    }
}

private suspend fun <R> AsyncHttpResponse.handle(handler: AsyncHttpResponseHandler<R>): R {
    try {
        return handler(this)
    }
    finally {
        close()
    }
}

internal val defaultMaxRedirects = 5
suspend fun <R> AsyncHttpClient.get(uri: String, handler: AsyncHttpResponseHandler<R>): R {
    return executeFollowRedirects(AsyncHttpRequest.GET(uri), defaultMaxRedirects, handler)
}

suspend fun <R> AsyncHttpClient.head(uri: String, handler: AsyncHttpResponseHandler<R>): R {
    return executeFollowRedirects(AsyncHttpRequest.HEAD(uri), defaultMaxRedirects, handler)
}

suspend fun <R> AsyncHttpClient.post(uri: String, handler: AsyncHttpResponseHandler<R>): R {
    return execute(AsyncHttpRequest.POST(uri), handler)
}

suspend fun AsyncHttpClient.randomAccess(uri: String): AsyncRandomAccessInput {
    val contentLength = head(uri) {
        if (it.headers["Accept-Ranges"] != "bytes")
            throw IOException("$uri can not fulfill range requests")
        it.headers.contentLength!!
    }

    var closed = false
    val currentReader = FreezableReference<AsyncHttpResponse?>()
    var currentPosition: Long = 0
    var currentRemaining: Long = 0
    val temp = ByteBufferList()

    return object : AsyncRandomAccessInput, AsyncAffinity by eventLoop {
        override suspend fun size(): Long {
            return contentLength
        }

        override suspend fun getPosition(): Long {
            return currentPosition
        }

        override suspend fun setPosition(position: Long) {
            currentReader.swap(null)?.value?.close()
        }

        override suspend fun readPosition(position: Long, length: Long, buffer: WritableBuffers): Boolean {
            if (currentReader.isFrozen)
                throw IOException("closed")

            if (currentPosition == contentLength)
                return false

            if (position + length > contentLength)
                throw IOException("invalid range")

            // check if the existing read can fulfill this request, and not go over the
            // requested length
            if (currentPosition != position || currentRemaining <= 0L || currentRemaining > length) {
                val headers = Headers()
                headers["Range"] = "bytes=$position-${position + length - 1}"
                val newRequest = AsyncHttpRequest.GET(uri, headers)
                val newResponse = execute(newRequest)
                val existing = currentReader.swap(newResponse)
                if (existing?.frozen == true)
                    newResponse.close()

                currentPosition = position
                currentRemaining = length
            }

            temp.takeReclaimedBuffers(buffer)
            if (!currentReader.get()!!.value!!.body!!(temp))
                return false

            currentPosition += temp.remaining()
            currentRemaining -= temp.remaining()
            temp.read(buffer)

            return true
        }

        override suspend fun read(buffer: WritableBuffers): Boolean {
            return readPosition(currentPosition, contentLength - currentPosition, buffer)
        }

        override suspend fun close() {
            currentReader.freeze(null)?.value?.close()
        }
    }
}
