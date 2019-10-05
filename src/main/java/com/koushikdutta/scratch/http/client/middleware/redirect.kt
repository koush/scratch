package com.koushikdutta.scratch.http.client.middleware

import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.client.*
import java.net.URI
import java.net.URL


private fun copyHeader(from: AsyncHttpRequest, to: AsyncHttpRequest, header: String) {
    val value = from.headers.get(header)
    if (value != null)
        to.headers.set(header, value)
}

class AsyncHttpRedirector : AsyncHttpClientMiddleware() {
    override suspend fun onBodyReady(session: AsyncHttpClientSession) {
        val location = session.response!!.headers.get("Location") ?: return

        var redirect = URI(location)
        if (redirect.scheme == null)
            redirect = URI(URL(URL(session.request.uri.toString()), location).toString())

        val method = if (session.request.method == AsyncHttpRequestMethods.HEAD.toString()) AsyncHttpRequestMethods.HEAD.toString() else AsyncHttpRequestMethods.GET.toString()
        val newRequest = AsyncHttpRequest(redirect, method)
        copyHeader(session.request, newRequest, "User-Agent")
        copyHeader(session.request, newRequest, "Range")

        var redirectCount = session.request.properties["redirectCount"] as Int?
        if (redirectCount != null && redirectCount > 5)
            throw AsyncHttpClientException("too many redirects")

        if (redirectCount == null)
            redirectCount = 1
        else
            redirectCount += 1

        newRequest.properties["redirectCount"] = redirectCount

        try {
            session.response = session.client.execute(newRequest)
        }
        catch (exception: Exception) {
            throw AsyncHttpClientException("Error while following redirect", exception)
        }
    }
}
