package com.koushikdutta.scratch.http

import com.koushikdutta.scratch.AsyncRead
import com.koushikdutta.scratch.http.client.AsyncHttpClientSwitchingProtocols
import com.koushikdutta.scratch.http.client.AsyncHttpDetachedSocket

private const val HTTP_1_1 = "HTTP/1.1"

internal fun create(code: Int, message: String, headers: Headers, body: AsyncHttpMessageBody?, sent: AsyncHttpMessageCompletion? = null): AsyncHttpResponse {
    return AsyncHttpResponse(ResponseLine(code, message, HTTP_1_1), headers, body, sent)
}

internal fun create(statusCode: StatusCode, headers: Headers, body: AsyncHttpMessageBody?, sent: AsyncHttpMessageCompletion? = null): AsyncHttpResponse {
    return AsyncHttpResponse(ResponseLine(statusCode.code, statusCode.message, HTTP_1_1), headers, body, sent)
}

fun AsyncHttpResponse.Companion.OK(headers: Headers = Headers(), body: AsyncHttpMessageBody? = null, sent: AsyncHttpMessageCompletion? = null) = create(StatusCode.OK, headers, body, sent)
fun AsyncHttpResponse.Companion.NOT_MODIFIED(headers: Headers = Headers(), sent: AsyncHttpMessageCompletion? = null) = create(StatusCode.NOT_MODIFIED, headers, null, sent)

fun AsyncHttpResponse.Companion.MOVED_PERMANENTLY(location: String, headers: Headers = Headers(), sent: AsyncHttpMessageCompletion? = null): AsyncHttpResponse {
    headers.set("Location", location)
    return create(StatusCode.MOVED_PERMANENTLY, headers, null, sent)
}
fun AsyncHttpResponse.Companion.FOUND(location: String, headers: Headers = Headers(), sent: AsyncHttpMessageCompletion? = null): AsyncHttpResponse {
    headers.set("Location", location)
    return create(StatusCode.FOUND, headers, null, sent)
}

fun AsyncHttpResponse.Companion.NOT_FOUND(headers: Headers = Headers(), body: AsyncHttpMessageBody? = null, sent: AsyncHttpMessageCompletion? = null) = create(StatusCode.NOT_FOUND, headers, body, sent)

fun AsyncHttpResponse.Companion.BAD_REQUEST(headers: Headers = Headers(), body: AsyncHttpMessageBody? = null, sent: AsyncHttpMessageCompletion? = null) = create(StatusCode.BAD_REQUEST, headers, body, sent)

fun AsyncHttpResponse.Companion.INTERNAL_SERVER_ERROR(headers: Headers = Headers(), body: AsyncHttpMessageBody? = null, sent: AsyncHttpMessageCompletion? = null) = create(StatusCode.INTERNAL_SERVER_ERROR, headers, body, sent)

internal class AsyncHttpResponseSwitchingProtocols(headers: Headers = Headers(), protocol: String = HTTP_1_1, internal val block: suspend(detachedSocket: AsyncHttpDetachedSocket) -> Unit)
    : AsyncHttpResponse(ResponseLine(StatusCode.SWITCHING_PROTOCOLS, protocol), headers, null as AsyncRead?, null)

fun AsyncHttpResponse.Companion.SWITCHING_PROTOCOLS(headers: Headers = Headers(), protocol: String = HTTP_1_1, block: suspend(detachedSocket: AsyncHttpDetachedSocket) -> Unit) : AsyncHttpResponse
    = AsyncHttpResponseSwitchingProtocols(headers, protocol, block)

enum class StatusCode(val code: Int, val message: String, val hasBody: Boolean = true) {
    SWITCHING_PROTOCOLS(101, "Switching Protocols", false),
    OK(200, "OK"),
    MOVED_PERMANENTLY(301, "Moved Permanently"),
    FOUND(302, "Found"),
    BAD_REQUEST(400, "Bad Request"),
    NOT_FOUND(404, "Not Found"),
    INTERNAL_SERVER_ERROR(500, "Internal Server Error"),
    NOT_MODIFIED(304, "Not Modified", false),
}
