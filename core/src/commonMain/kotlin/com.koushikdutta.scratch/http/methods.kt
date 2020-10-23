package com.koushikdutta.scratch.http

import com.koushikdutta.scratch.uri.URI

enum class Methods(val responseHasBody: Boolean = true) {
    CONNECT(false),
    HEAD(false),
    GET,
    DELETE,
    POST,
    PUT;

    operator fun invoke(uri: String, headers: Headers = Headers(), body: AsyncHttpMessageBody? = null, sent: AsyncHttpMessageCompletion? = null): AsyncHttpRequest {
        return AsyncHttpRequest(URI.create(uri), toString(), headers = headers, body = body, sent = sent)
    }
}
