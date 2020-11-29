package com.koushikdutta.scratch.http

import com.koushikdutta.scratch.AsyncRead
import com.koushikdutta.scratch.uri.URI

enum class Methods(val responseHasBody: Boolean = true) {
    CONNECT(false),
    HEAD(false),
    GET,
    DELETE,
    POST,
    PUT;

    operator fun invoke(uri: String, headers: Headers = Headers(), body: AsyncRead? = null, sent: AsyncHttpMessageCompletion? = null): AsyncHttpRequest {
        return AsyncHttpRequest(URI(uri), toString(), headers = headers, body = body, sent = sent)
    }

    operator fun invoke(uri: String, headers: Headers = Headers(), body: AsyncHttpMessageContent, sent: AsyncHttpMessageCompletion? = null): AsyncHttpRequest {
        return AsyncHttpRequest(URI(uri), toString(), headers = headers, body = body, sent = AsyncHttpMessageContent.prepare(headers, body, sent))
    }
}
