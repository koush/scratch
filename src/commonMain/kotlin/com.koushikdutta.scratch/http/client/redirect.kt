package com.koushikdutta.scratch.http.client

import com.koushikdutta.scratch.drain
import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.uri.URI

private fun copyHeader(from: AsyncHttpRequest, to: AsyncHttpRequest, header: String) {
    val value = from.headers.get(header)
    if (value != null)
        to.headers.set(header, value)
}

suspend fun <R> AsyncHttpClient.executeFollowRedirects(request: AsyncHttpRequest, maxRedirects: Int = defaultMaxRedirects, handler: AsyncHttpResponseHandler<R>): R {
    return execute(request) {
        val response = it
        val responseCode = response.code
        // valid redirects
        if (responseCode != 301 && responseCode != 302 && responseCode != 307)
            return@execute handler(response)

        // drain the body to allow socket reuse.
        response.body?.drain()

        if (maxRedirects <= 0)
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

        executeFollowRedirects(newRequest, maxRedirects - 1, handler)
    }
}
