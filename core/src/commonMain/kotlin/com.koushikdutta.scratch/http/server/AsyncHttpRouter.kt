package com.koushikdutta.scratch.http.server

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.buffers.WritableBuffers
import com.koushikdutta.scratch.http.*
import com.koushikdutta.scratch.http.body.BinaryBody
import com.koushikdutta.scratch.http.websocket.WebSocketServerSocket
import com.koushikdutta.scratch.http.websocket.upgradeWebsocket

class AsyncHttpRouteHandlerScope(request: AsyncHttpRequest, val match: MatchResult) : AsyncHttpResponseScope(request)

typealias AsyncRouterResponseHandler = suspend AsyncHttpRouteHandlerScope.() -> AsyncHttpResponse

interface AsyncHttpRouteHandler {
    suspend operator fun AsyncHttpResponseScope.invoke(): AsyncHttpResponse?
}

class AsyncHttpRouter(private val onRequest: suspend (request: AsyncHttpRequest) -> Unit = {}) : AsyncHttpRouteHandler {
    override suspend operator fun AsyncHttpResponseScope.invoke(): AsyncHttpResponse? = invoke(request)

    suspend operator fun invoke(request: AsyncHttpRequest): AsyncHttpResponse? {
        onRequest(request)

        for (entry in routes) {
            val route = entry.key
            val handler = entry.value
            if (route.method != null && route.method != request.method)
                continue
            val match = route.pathRegex.matchEntire(request.uri.path ?: "")
            if (match != null)
                return handler(AsyncHttpRouteHandlerScope(request, match))
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

    suspend fun handle(responseScope: AsyncHttpResponseScope): AsyncHttpResponse {
        val response = this(responseScope.request)
        if (response != null)
            return response
        return StatusCode.NOT_FOUND()
    }
}


fun AsyncHttpRouter.head(pathRegex: String, handler: AsyncRouterResponseHandler) = set("HEAD", pathRegex, handler)
fun AsyncHttpRouter.get(pathRegex: String, handler: AsyncRouterResponseHandler) = set("GET", pathRegex, handler)
fun AsyncHttpRouter.post(pathRegex: String, handler: AsyncRouterResponseHandler) = set("POST", pathRegex, handler)
fun AsyncHttpRouter.put(pathRegex: String, handler: AsyncRouterResponseHandler) = set("PUT", pathRegex, handler)

fun AsyncHttpRouter.randomAccessSlice(pathRegex: String, handler: suspend AsyncHttpRouteHandlerScope.(headers: Headers) -> AsyncSliceable?) {
    set(null, pathRegex) {
        val headers = Headers()
        val input = handler(headers)
        if (input == null)
            return@set StatusCode.NOT_FOUND()
        createResponse(input, headers)
    }
    head(pathRegex) {
        val headers = Headers()
        val input = handler(headers)
        if (input == null)
            return@head StatusCode.NOT_FOUND()
        headers.set("Accept-Ranges", "bytes")
        headers.set("Content-Length", input.size().toString())
        return@head StatusCode.OK(headers)
    }

    get(pathRegex) {
        val headers = Headers()
        val input = handler(headers)
        if (input == null)
            return@get StatusCode.NOT_FOUND()

        val totalLength = input.size()

        headers["Accept-Ranges"] = "bytes"

        val range = request.headers.get("range")
        var start = 0L
        var end: Long = totalLength - 1L
        var code = 200

        val asyncInput: AsyncInput
        if (range != null) {
            var parts = range.split("=").toTypedArray()
            // Requested range not satisfiable
            if (parts.size != 2 || "bytes" != parts[0])
                return@get AsyncHttpResponse(ResponseLine(StatusCode.NOT_SATISFIABLE))

            parts = parts[1].split("-").toTypedArray()
            try {
                if (parts.size > 2) throw IllegalArgumentException()
                if (!parts[0].isEmpty()) start = parts[0].toLong()
                end = if (parts.size == 2 && !parts[1].isEmpty()) parts[1].toLong() else totalLength - 1
                code = 206
                headers.set("Content-Range", "bytes $start-$end/$totalLength")
            }
            catch (e: Throwable) {
                return@get AsyncHttpResponse(ResponseLine(StatusCode.NOT_SATISFIABLE))
            }

            asyncInput = input.slice(start, end - start + 1)
        }
        else {
            asyncInput = input.slice(0, input.size())
        }

        if (code == 200) {
            StatusCode.OK(body = BinaryBody(asyncInput::read, contentLength = totalLength), headers = headers) {
                asyncInput.close()
            }
        }
        else {
            AsyncHttpResponse(body = BinaryBody(asyncInput::read, contentLength = end - start + 1), headers = headers, responseLine = ResponseLine(code, "Partial Content", "HTTP/1.1")) {
                asyncInput.close()
            }
        }
    }
}

fun AsyncHttpRouter.randomAccessInput(pathRegex: String, handler: suspend AsyncHttpRouteHandlerScope.(headers: Headers) -> AsyncRandomAccessInput?) {
    return randomAccessSlice(pathRegex) { headers ->
        val input = handler(headers)
        if (input == null)
            return@randomAccessSlice null

        return@randomAccessSlice object : AsyncSliceable {
            override suspend fun size(): Long {
                return input.size()
            }

            override suspend fun slice(position: Long, length: Long): AsyncInput {
                // will only be called once.
                val sliced = input.slice(position, length)

                return object : AsyncInput, AsyncAffinity by input {
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
        upgradeWebsocket(protocol) {
            serverSocket.queue.add(it)
        }
    }
    return serverSocket
}