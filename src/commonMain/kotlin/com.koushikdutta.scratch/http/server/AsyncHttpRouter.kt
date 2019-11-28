package com.koushikdutta.scratch.http.server

import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.AsyncHttpResponse
import com.koushikdutta.scratch.http.NOT_FOUND

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


fun AsyncHttpRouter.get(pathRegex: String, handler: AsyncRouterResponseHandler) = add("GET", pathRegex, handler)
fun AsyncHttpRouter.post(pathRegex: String, handler: AsyncRouterResponseHandler) = add("POST", pathRegex, handler)
fun AsyncHttpRouter.put(pathRegex: String, handler: AsyncRouterResponseHandler) = add("PUT", pathRegex, handler)

