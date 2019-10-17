package com.koushikdutta.scratch.http

import com.koushikdutta.scratch.uri.URI

private fun create(uri: String, method: String, headers: Headers = Headers(), body: AsyncHttpMessageBody? = null): AsyncHttpRequest {
    return AsyncHttpRequest(URI.create(uri), method, headers = headers, body = body)
}

fun AsyncHttpRequest.Companion.GET(uri: String, headers: Headers = Headers()) = create(uri, "GET", headers)
fun AsyncHttpRequest.Companion.DELETE(uri: String, headers: Headers = Headers()) = create(uri, "DELETE", headers)
fun AsyncHttpRequest.Companion.POST(uri: String, headers: Headers = Headers(), body: AsyncHttpMessageBody? = null) = create(uri, "POST", headers, body)
fun AsyncHttpRequest.Companion.PUT(uri: String, headers: Headers = Headers(), body: AsyncHttpMessageBody? = null) = create(uri, "PUT", headers, body)
