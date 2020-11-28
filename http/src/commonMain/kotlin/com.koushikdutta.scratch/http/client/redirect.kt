package com.koushikdutta.scratch.http.client

import com.koushikdutta.scratch.siphon
import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.AsyncHttpResponse
import com.koushikdutta.scratch.http.Methods
import com.koushikdutta.scratch.http.client.executor.AsyncHttpClientExecutor
import com.koushikdutta.scratch.http.client.executor.AsyncHttpClientWrappingExecutor
import com.koushikdutta.scratch.uri.URI
import com.koushikdutta.scratch.uri.scheme

private fun copyHeader(from: AsyncHttpRequest, to: AsyncHttpRequest, header: String) {
    val value = from.headers[header]
    if (value != null)
        to.headers[header] = value
}

private val defaultMaxRedirects = 5

private class RedirectExecutor(override val next: AsyncHttpClientExecutor, val maxRedirects: Int = defaultMaxRedirects) : AsyncHttpClientWrappingExecutor {
    private suspend fun handleRedirects(redirects: Int, request: AsyncHttpRequest, response: AsyncHttpResponse): AsyncHttpResponse {
        val newRequest = checkRedirect(request, response)
        if (newRequest == null)
            return response

        response.body?.siphon()

        if (redirects <= 0)
            throw Exception("Too many redirects")

        return handleRedirects(redirects - 1,  newRequest, next(newRequest))
    }

    override suspend operator fun invoke(request: AsyncHttpRequest): AsyncHttpResponse {
        return handleRedirects(maxRedirects, request, next(request))
    }

    companion object {
        fun checkRedirect(request: AsyncHttpRequest, response: AsyncHttpResponse): AsyncHttpRequest? {
            val responseCode = response.code
            if (responseCode != 301 && responseCode != 302 && responseCode != 307)
                return null

            val location = response.headers.get("Location")
            if (location == null)
                throw Exception("Location header missing on redirect")

            var redirect = URI(location)
            if (redirect.scheme == null)
                redirect = request.uri.resolve(location)

            val method = if (request.method == Methods.HEAD.toString()) Methods.HEAD else Methods.GET
            val newRequest = AsyncHttpRequest(URI(redirect.toString()), method.toString())
            copyHeader(request, newRequest, "User-Agent")
            copyHeader(request, newRequest, "Range")

            return newRequest
        }
    }
}

fun AsyncHttpExecutorBuilder.followRedirects(maxRedirects: Int = defaultMaxRedirects): AsyncHttpExecutorBuilder {
    wrapExecutor {
        RedirectExecutor(it, maxRedirects)
    }
    return this
}

