package com.koushikdutta.scratch.http.server

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.buffers.WritableBuffers
import com.koushikdutta.scratch.http.*
import com.koushikdutta.scratch.http.websocket.WebSocketServerSocket
import com.koushikdutta.scratch.http.websocket.upgradeWebsocket
import com.koushikdutta.scratch.uri.path

open class AsyncHttpRouteHandlerScope(val match: MatchResult)
class AsyncHttpRouterResultHandlerScope(val headers: Headers, match: MatchResult): AsyncHttpRouteHandlerScope(match)

typealias AsyncRouterResponseHandler = suspend AsyncHttpRouteHandlerScope.(request: AsyncHttpRequest) -> AsyncHttpResponse?

interface AsyncHttpRouteHandler {
    suspend operator fun AsyncHttpRouteHandlerScope.invoke(request: AsyncHttpRequest): AsyncHttpResponse?
}

open class AsyncHttpRouter(private val onRequest: suspend (request: AsyncHttpRequest) -> Unit = {}) : AsyncHttpRouteHandler {
    override suspend operator fun AsyncHttpRouteHandlerScope.invoke(request: AsyncHttpRequest): AsyncHttpResponse? = invoke(request)

    class RouteMatch(val match: MatchResult, val handler: AsyncRouterResponseHandler)

    fun match(method: String, path: String): List<RouteMatch> {
        val ret = mutableListOf<RouteMatch>()

        for (entry in routes) {
            val route = entry.key
            val handler = entry.value
            if (route.method != null && route.method != method)
                continue
            val match = route.pathRegex.matchEntire(path ?: "")
            if (match != null)
                ret.add(RouteMatch(match, handler))
        }

        return ret
    }

    suspend operator fun invoke(request: AsyncHttpRequest): AsyncHttpResponse? {
        onRequest(request)

        val matches = match(request.method, request.uri.path ?: "")
        for (match in matches) {
            val response = match.handler(AsyncHttpRouteHandlerScope(match.match), request)
            if (response != null)
                return response
        }

        return null
    }

    private class Route(val method: String?, val pathRegexString: String) {
        val pathRegex = Regex(pathRegexString)

        override fun equals(other: Any?): Boolean {
            if (other !is Route)
                return false
            return other.method == method && other.pathRegexString == pathRegexString
        }

        override fun hashCode(): Int {
            if (method != null)
                return method.hashCode() xor pathRegexString.hashCode()
            return pathRegexString.hashCode()
        }
    }
    private val routes = mutableMapOf<Route, AsyncRouterResponseHandler>()

    fun set(method: String?, pathRegex: String, handler: AsyncRouterResponseHandler) {
        routes[Route(method?.toUpperCase(), pathRegex)] = handler
    }

    suspend fun handle(request: AsyncHttpRequest): AsyncHttpResponse {
        val response = this(request)
        if (response != null)
            return response
        return StatusCode.NOT_FOUND()
    }
}


fun AsyncHttpRouter.head(pathRegex: String, handler: AsyncRouterResponseHandler) = set("HEAD", pathRegex, handler)
fun AsyncHttpRouter.get(pathRegex: String, handler: AsyncRouterResponseHandler) = set("GET", pathRegex, handler)
fun AsyncHttpRouter.post(pathRegex: String, handler: AsyncRouterResponseHandler) = set("POST", pathRegex, handler)
fun AsyncHttpRouter.put(pathRegex: String, handler: AsyncRouterResponseHandler) = set("PUT", pathRegex, handler)

fun AsyncHttpRouter.randomAccessSlice(pathRegex: String, handler: suspend AsyncHttpRouterResultHandlerScope.(request: AsyncHttpRequest) -> AsyncSliceable?) {
    set(null, pathRegex) {
        val headers = Headers()
        val input = handler(AsyncHttpRouterResultHandlerScope(headers, match), it)
        if (input == null)
            return@set StatusCode.NOT_FOUND()
        it.createSliceableResponse(input.size(), headers, input::slice)
    }
}

fun AsyncHttpRouter.randomAccessInput(pathRegex: String, handler: suspend AsyncHttpRouterResultHandlerScope.(request: AsyncHttpRequest) -> AsyncRandomAccessInput?) {
    return randomAccessSlice(pathRegex) {
        val input = handler(this, it)
        if (input == null)
            return@randomAccessSlice null

        return@randomAccessSlice object : AsyncSliceable {
            override suspend fun size(): Long {
                return input.size()
            }

            override suspend fun slice(position: Long, length: Long): AsyncInput {
                // will only be called once.
                val sliced = input.seekRead(position, length)

                return object : AsyncInput {
                    override suspend fun read(buffer: WritableBuffers) = sliced(buffer)
                    override suspend fun close() = input.close()
                }
            }
        }
    }
}

fun AsyncHttpRouter.webSocket(pathRegex: String, protocol: String? = null): WebSocketServerSocket {
    val serverSocket = WebSocketServerSocket()
    get(pathRegex) {
        it.upgradeWebsocket(protocol) {
            serverSocket.queue.add(it)
        }
    }
    return serverSocket
}