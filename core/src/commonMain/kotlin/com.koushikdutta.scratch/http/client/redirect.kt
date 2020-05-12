package com.koushikdutta.scratch.http.client

import AsyncHttpExecutor
import AsyncHttpExecutorBuilder
import com.koushikdutta.scratch.drain
import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.AsyncHttpResponse
import com.koushikdutta.scratch.uri.URI
import execute

private fun copyHeader(from: AsyncHttpRequest, to: AsyncHttpRequest, header: String) {
    val value = from.headers.get(header)
    if (value != null)
        to.headers.set(header, value)
}

private val defaultMaxRedirects = 5

private class RedirectExecutor(val next: AsyncHttpExecutor, val maxRedirects: Int = defaultMaxRedirects) : AsyncHttpExecutor {
    override val client = next.client

    private suspend fun handleRedirects(redirects: Int, executor: AsyncHttpExecutor, request: AsyncHttpRequest, response: AsyncHttpResponse): AsyncHttpResponse {
        val responseCode = response.code
        // valid redirects
        if (responseCode != 301 && responseCode != 302 && responseCode != 307)
            return response

        // drain the body to allow socket reuse.
        response.body?.drain()

        if (redirects <= 0)
            throw Exception("Too many redirects")

        val location = response.headers.get("Location")
        if (location == null)
            throw Exception("Location header missing on redirect")

        var redirect = URI(location)
        if (redirect.scheme == null)
            redirect = request.uri.resolve(location)

        val method = if (request.method == AsyncHttpRequestMethods.HEAD.toString()) AsyncHttpRequestMethods.HEAD.toString() else AsyncHttpRequestMethods.GET.toString()
        val newRequest = AsyncHttpRequest(URI.create(redirect.toString()), method)
        copyHeader(request, newRequest, "User-Agent")
        copyHeader(request, newRequest, "Range")

        return handleRedirects(redirects - 1, executor, newRequest, executor.execute(newRequest))
    }

    override suspend fun execute(session: AsyncHttpClientSession): AsyncHttpResponse {
        return handleRedirects(maxRedirects, session.executor, session.request, next.execute(session))
    }
}

fun AsyncHttpExecutorBuilder.followRedirects(maxRedirects: Int = defaultMaxRedirects): AsyncHttpExecutorBuilder {
    wrapExecutor {
        RedirectExecutor(it, maxRedirects)
    }
    return this
}

