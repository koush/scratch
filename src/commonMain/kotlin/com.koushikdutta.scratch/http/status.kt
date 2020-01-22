package com.koushikdutta.scratch.http

internal fun create(code: Int, message: String, headers: Headers, body: AsyncHttpMessageBody?, sent: AsyncHttpMessageCompletion? = null): AsyncHttpResponse {
    return AsyncHttpResponse(ResponseLine(code, message, "HTTP/1.1"), headers, body, sent)
}

internal fun create(statusCode: StatusCode, headers: Headers, body: AsyncHttpMessageBody?, sent: AsyncHttpMessageCompletion? = null): AsyncHttpResponse {
    return AsyncHttpResponse(ResponseLine(statusCode.code, statusCode.message, "HTTP/1.1"), headers, body, sent)
}

fun AsyncHttpResponse.Companion.OK(headers: Headers = Headers(), body: AsyncHttpMessageBody? = null, sent: AsyncHttpMessageCompletion? = null) = create(StatusCode.OK, headers, body, sent)

fun AsyncHttpResponse.Companion.MOVED_PERMANENTLY(location: String, headers: Headers = Headers(), sent: AsyncHttpMessageCompletion? = null): AsyncHttpResponse {
    headers.set("Location", location)
    return create(StatusCode.MOVED_PERMANENTLY, headers, null, sent)
}
fun AsyncHttpResponse.Companion.FOUND(location: String, headers: Headers = Headers(), sent: AsyncHttpMessageCompletion? = null): AsyncHttpResponse {
    headers.set("Location", location)
    return create(StatusCode.FOUND, headers, null, sent)
}

fun AsyncHttpResponse.Companion.NOT_FOUND(headers: Headers = Headers(), body: AsyncHttpMessageBody? = null, sent: AsyncHttpMessageCompletion? = null) = create(StatusCode.NOT_FOUND, headers, body, sent)

fun AsyncHttpResponse.Companion.INTERNAL_SERVER_ERROR(headers: Headers = Headers(), body: AsyncHttpMessageBody? = null, sent: AsyncHttpMessageCompletion? = null) = create(StatusCode.INTERNAL_SERVER_ERROR, headers, body, sent)

enum class StatusCode(val code: Int, val message: String) {
    SWITCHING_PROTOCOLS(101, "Switching Protocols"),
    OK(200, "OK"),
    MOVED_PERMANENTLY(301, "Moved Permanently"),
    FOUND(302, "Found"),
    NOT_FOUND(404, "Not Found"),
    INTERNAL_SERVER_ERROR(500, "Internal Server Error"),
}
