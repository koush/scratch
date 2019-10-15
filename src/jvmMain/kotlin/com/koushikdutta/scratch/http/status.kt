package com.koushikdutta.scratch.http

internal fun create(code: Int, message: String, headers: Headers, body: AsyncHttpMessageBody?): AsyncHttpResponse {
    return AsyncHttpResponse(ResponseLine(code, message, "HTTP/1.1"), headers, body)
}

fun AsyncHttpResponse.Companion.OK(headers: Headers = Headers(), body: AsyncHttpMessageBody? = null) = create(200, "OK", headers, body)

fun AsyncHttpResponse.Companion.MOVED_PERMANENTLY(location: String, headers: Headers = Headers()): AsyncHttpResponse {
    headers.set("Location", location)
    return create(301, "Moved Permanently", headers, null)
}
fun AsyncHttpResponse.Companion.FOUND(location: String, headers: Headers = Headers()): AsyncHttpResponse {
    headers.set("Location", location)
    return create(302, "Found", headers, null)
}

fun AsyncHttpResponse.Companion.NOT_FOUND(headers: Headers = Headers(), body: AsyncHttpMessageBody? = null) = create(404, "Not Found", headers, body)

fun AsyncHttpResponse.Companion.INTERNAL_SERVER_ERROR(headers: Headers = Headers(), body: AsyncHttpMessageBody? = null) = create(500, "Internal Server Error", headers, body)

