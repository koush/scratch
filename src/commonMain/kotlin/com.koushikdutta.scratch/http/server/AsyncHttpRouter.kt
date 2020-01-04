package com.koushikdutta.scratch.http.server

import com.koushikdutta.scratch.AsyncRandomAccessInput
import com.koushikdutta.scratch.AsyncRead
import com.koushikdutta.scratch.http.*
import com.koushikdutta.scratch.http.body.BinaryBody
import com.koushikdutta.scratch.slice

typealias AsyncRouterResponseHandler = suspend (request: AsyncHttpRequest, matchResult: MatchResult) -> AsyncHttpResponse

interface AsyncHttpRouteHandler {
    suspend operator fun invoke(request: AsyncHttpRequest): AsyncHttpResponse?
}

class AsyncHttpRouter : AsyncHttpRouteHandler {
    override suspend operator fun invoke(request: AsyncHttpRequest): AsyncHttpResponse? {
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

fun AsyncHttpRouter.randomAccessInput(pathRegex: String, handler: suspend (request: AsyncHttpRequest, matchResult: MatchResult) -> AsyncRandomAccessInput?) {
    head(pathRegex) { request, matchResult ->
        val input = handler(request, matchResult)
        if (input == null)
            return@head AsyncHttpResponse.NOT_FOUND()
        val headers = Headers()
        headers.set("Accept-Ranges", "bytes")
        headers.set("Content-Length", input.size().toString())
        return@head AsyncHttpResponse.OK(headers)
    }

    get(pathRegex) { request, matchResult ->
        val input = handler(request, matchResult)
        if (input == null)
            return@get AsyncHttpResponse.NOT_FOUND()

        val totalLength = input.size()

        val headers = Headers()
        headers.set("Accept-Ranges", "bytes")
        headers.set("Content-Length", totalLength.toString())

        val range = request.headers.get("range")
        var start = 0L
        var end: Long = totalLength - 1L
        var code = 200

        val read: AsyncRead
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

            read = input.slice(start, end + 1)
        }
        else {
            read = input::read
        }

        if (code == 200)
            AsyncHttpResponse.OK(body = BinaryBody(read), headers = headers)
        else
            AsyncHttpResponse(body = BinaryBody(read), headers = headers, responseLine = ResponseLine(code, "Partial Content", "HTTP/1.1"))
    }
}
