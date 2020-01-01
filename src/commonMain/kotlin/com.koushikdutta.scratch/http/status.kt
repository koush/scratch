package com.koushikdutta.scratch.http

internal fun create(code: Int, message: String, headers: Headers, body: AsyncHttpMessageBody?, sent: AsyncHttpMessageCompletion? = null): AsyncHttpResponse {
    return AsyncHttpResponse(ResponseLine(code, message, "HTTP/1.1"), headers, body, sent)
}

fun AsyncHttpResponse.Companion.OK(headers: Headers = Headers(), body: AsyncHttpMessageBody? = null, sent: AsyncHttpMessageCompletion? = null) = create(200, "OK", headers, body, sent)

fun AsyncHttpResponse.Companion.MOVED_PERMANENTLY(location: String, headers: Headers = Headers(), sent: AsyncHttpMessageCompletion? = null): AsyncHttpResponse {
    headers.set("Location", location)
    return create(301, "Moved Permanently", headers, null, sent)
}
fun AsyncHttpResponse.Companion.FOUND(location: String, headers: Headers = Headers(), sent: AsyncHttpMessageCompletion? = null): AsyncHttpResponse {
    headers.set("Location", location)
    return create(302, "Found", headers, null, sent)
}

fun AsyncHttpResponse.Companion.NOT_FOUND(headers: Headers = Headers(), body: AsyncHttpMessageBody? = null, sent: AsyncHttpMessageCompletion? = null) = create(404, "Not Found", headers, body, sent)

fun AsyncHttpResponse.Companion.INTERNAL_SERVER_ERROR(headers: Headers = Headers(), body: AsyncHttpMessageBody? = null, sent: AsyncHttpMessageCompletion? = null) = create(500, "Internal Server Error", headers, body, sent)

