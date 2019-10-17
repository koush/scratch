package com.koushikdutta.scratch.http.client.middleware

import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.AsyncHttpRequestProperties
import com.koushikdutta.scratch.http.client.AsyncHttpClientException
import com.koushikdutta.scratch.http.client.AsyncHttpClientSession
import com.koushikdutta.scratch.http.client.AsyncHttpRequestMethods
import com.koushikdutta.scratch.uri.URI


private fun copyHeader(from: AsyncHttpRequest, to: AsyncHttpRequest, header: String) {
    val value = from.headers.get(header)
    if (value != null)
        to.headers.set(header, value)
}

private const val REQUEST_PROPERTY_FOLLOW_REDIRECT = "follow-redirect"

// follow by default
var AsyncHttpRequestProperties.followRedirect: Boolean
    get() = this[REQUEST_PROPERTY_FOLLOW_REDIRECT] != false
    set(value) { this[REQUEST_PROPERTY_FOLLOW_REDIRECT] = value }


class AsyncHttpRedirector : AsyncHttpClientMiddleware() {
    override suspend fun onBodyReady(session: AsyncHttpClientSession) {
        val responseCode = session.response!!.code
        // valid redirects
        if (responseCode != 301 && responseCode != 302 && responseCode != 307)
            return

        if (!session.request.properties.followRedirect)
            return

        val location = session.response!!.headers.get("Location") ?: return

        var redirect = URI(location)
        if (redirect.scheme == null)
            redirect = session.request.uri.resolve(location)

        val method = if (session.request.method == AsyncHttpRequestMethods.HEAD.toString()) AsyncHttpRequestMethods.HEAD.toString() else AsyncHttpRequestMethods.GET.toString()
        val newRequest = AsyncHttpRequest(com.koushikdutta.scratch.uri.URI.create(redirect.toString()), method)
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
