package com.koushikdutta.scratch.http.client

import com.koushikdutta.scratch.drain
import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.AsyncHttpResponse
import com.koushikdutta.scratch.http.Methods
import com.koushikdutta.scratch.http.client.executor.AsyncHttpClientExecutor
import com.koushikdutta.scratch.uri.URI

private fun copyHeader(from: AsyncHttpRequest, to: AsyncHttpRequest, header: String) {
    val value = from.headers[header]
    if (value != null)
        to.headers[header] = value
}

private val defaultMaxRedirects = 5

private class RedirectExecutor(val next: AsyncHttpClientExecutor, val maxRedirects: Int = defaultMaxRedirects) : AsyncHttpClientExecutor {
    override val affinity = next.affinity

    private suspend fun handleRedirects(redirects: Int, request: AsyncHttpRequest, response: AsyncHttpResponse): AsyncHttpResponse {
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

        val method = if (request.method == Methods.HEAD.toString()) Methods.HEAD else Methods.GET
        val newRequest = AsyncHttpRequest(URI.create(redirect.toString()), method.toString())
        copyHeader(request, newRequest, "User-Agent")
        copyHeader(request, newRequest, "Range")

        return handleRedirects(redirects - 1,  newRequest, next(newRequest))
    }

    override suspend operator fun invoke(request: AsyncHttpRequest): AsyncHttpResponse {
        return handleRedirects(maxRedirects, request, next(request))
    }
}

fun AsyncHttpExecutorBuilder.followRedirects(maxRedirects: Int = defaultMaxRedirects): AsyncHttpExecutorBuilder {
    wrapExecutor {
        RedirectExecutor(it, maxRedirects)
    }
    return this
}

