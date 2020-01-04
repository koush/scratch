package com.koushikdutta.scratch.http

import com.koushikdutta.scratch.uri.URI

private fun create(uri: String, method: String, headers: Headers = Headers(), body: AsyncHttpMessageBody? = null, sent: AsyncHttpMessageCompletion? = null): AsyncHttpRequest {
    return AsyncHttpRequest(URI.create(uri), method, headers = headers, body = body, sent = sent)
}

fun AsyncHttpRequest.Companion.HEAD(uri: String, headers: Headers = Headers(), sent: AsyncHttpMessageCompletion? = null) = create(uri, "HEAD", headers, sent = sent)
fun AsyncHttpRequest.Companion.GET(uri: String, headers: Headers = Headers(), sent: AsyncHttpMessageCompletion? = null) = create(uri, "GET", headers, sent = sent)
fun AsyncHttpRequest.Companion.DELETE(uri: String, headers: Headers = Headers(), sent: AsyncHttpMessageCompletion? = null) = create(uri, "DELETE", headers, sent = sent)
fun AsyncHttpRequest.Companion.POST(uri: String, headers: Headers = Headers(), body: AsyncHttpMessageBody? = null, sent: AsyncHttpMessageCompletion? = null) = create(uri, "POST", headers, body, sent)
fun AsyncHttpRequest.Companion.PUT(uri: String, headers: Headers = Headers(), body: AsyncHttpMessageBody? = null, sent: AsyncHttpMessageCompletion? = null) = create(uri, "PUT", headers, body, sent)
