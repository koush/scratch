package com.koushikdutta.scratch.http.server

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.buffers.WritableBuffers
import com.koushikdutta.scratch.http.*
import com.koushikdutta.scratch.http.body.BinaryBody

typealias AsyncRouterResponseHandler = suspend (request: AsyncHttpRequest, matchResult: MatchResult) -> AsyncHttpResponse

interface AsyncHttpRouteHandler {
    suspend operator fun invoke(request: AsyncHttpRequest): AsyncHttpResponse?
}

class AsyncHttpRouter(private val onRequest: suspend (request: AsyncHttpRequest) -> Unit = {}) : AsyncHttpRouteHandler {
    override suspend operator fun invoke(request: AsyncHttpRequest): AsyncHttpResponse? {
        onRequest(request)

        for (route in routes) {
            if (route.method != null && route.method != request.method)
                continue
            val match = route.pathRegex.matchEntire(request.uri.path ?: "")
            if (match != null)
                return route.handler(request, match)
        }

        return null
    }

    private class Route(val method: String?, val pathRegex: Regex, val handler: AsyncRouterResponseHandler)
    private val routes = mutableListOf<Route>()

    fun add(method: String?, pathRegex: String, handler: AsyncRouterResponseHandler) {
        routes.add(Route(method?.toUpperCase(), Regex(pathRegex), handler))
    }

    suspend fun handle(request: AsyncHttpRequest): AsyncHttpResponse {
        val response = this(request)
        if (response != null)
            return response
        return AsyncHttpResponse.NOT_FOUND()
    }
}


fun AsyncHttpRouter.head(pathRegex: String, handler: AsyncRouterResponseHandler) = add("HEAD", pathRegex, handler)
fun AsyncHttpRouter.get(pathRegex: String, handler: AsyncRouterResponseHandler) = add("GET", pathRegex, handler)
fun AsyncHttpRouter.post(pathRegex: String, handler: AsyncRouterResponseHandler) = add("POST", pathRegex, handler)
fun AsyncHttpRouter.put(pathRegex: String, handler: AsyncRouterResponseHandler) = add("PUT", pathRegex, handler)

fun AsyncHttpRouter.randomAccessSlice(pathRegex: String, handler: suspend (headers: Headers, request: AsyncHttpRequest, matchResult: MatchResult) -> AsyncSliceable?) {
    head(pathRegex) { request, matchResult ->
        val headers = Headers()
        val input = handler(headers, request, matchResult)
        if (input == null)
            return@head AsyncHttpResponse.NOT_FOUND()
        headers.set("Accept-Ranges", "bytes")
        headers.set("Content-Length", input.size().toString())
        return@head AsyncHttpResponse.OK(headers)
    }

    get(pathRegex) { request, matchResult ->
        val headers = Headers()
        val input = handler(headers, request, matchResult)
        if (input == null)
            return@get AsyncHttpResponse.NOT_FOUND()

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
                return@get AsyncHttpResponse(ResponseLine(416, "Not Satisfiable", "HTTP/1.1"))

            parts = parts[1].split("-").toTypedArray()
            try {
                if (parts.size > 2) throw IllegalArgumentException()
                if (!parts[0].isEmpty()) start = parts[0].toLong()
                end = if (parts.size == 2 && !parts[1].isEmpty()) parts[1].toLong() else totalLength - 1
                code = 206
                headers.set("Content-Range", "bytes $start-$end/$totalLength")
            }
            catch (e: Throwable) {
                return@get AsyncHttpResponse(ResponseLine(416, "Not Satisfiable", "HTTP/1.1"))
            }

            asyncInput = input.slice(start, end - start + 1)
        }
        else {
            asyncInput = input.slice(0, input.size())
        }

        if (code == 200) {
            AsyncHttpResponse.OK(body = BinaryBody(asyncInput::read, contentLength =  totalLength), headers = headers) {
                asyncInput.close()
            }
        }
        else {
            AsyncHttpResponse(body = BinaryBody(asyncInput::read, contentLength =  totalLength), headers = headers, responseLine = ResponseLine(code, "Partial Content", "HTTP/1.1")) {
                asyncInput.close()
            }
        }
    }
}

fun AsyncHttpRouter.randomAccessInput(pathRegex: String, handler: suspend (headers: Headers, request: AsyncHttpRequest, matchResult: MatchResult) -> AsyncRandomAccessInput?) {
    return randomAccessSlice(pathRegex) { headers, request, match ->
        val input = handler(headers, request, match)
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
