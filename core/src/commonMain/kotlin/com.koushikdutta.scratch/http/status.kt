package com.koushikdutta.scratch.http

import com.koushikdutta.scratch.AsyncRead
import com.koushikdutta.scratch.http.client.AsyncHttpClientSwitchingProtocols
import com.koushikdutta.scratch.http.client.AsyncHttpDetachedSocket

private const val HTTP_1_1 = "HTTP/1.1"

internal class AsyncHttpResponseSwitchingProtocols(headers: Headers = Headers(), protocol: String = HTTP_1_1, internal val block: suspend(detachedSocket: AsyncHttpDetachedSocket) -> Unit)
    : AsyncHttpResponse(ResponseLine(StatusCode.SWITCHING_PROTOCOLS, protocol), headers, null as AsyncRead?, null)

fun AsyncHttpResponse.Companion.SWITCHING_PROTOCOLS(headers: Headers = Headers(), protocol: String = HTTP_1_1, block: suspend(detachedSocket: AsyncHttpDetachedSocket) -> Unit) : AsyncHttpResponse
    = AsyncHttpResponseSwitchingProtocols(headers, protocol, block)

enum class StatusCode(val code: Int, val message: String, val hasBody: Boolean = true) {
    SWITCHING_PROTOCOLS(101, "Switching Protocols", false),
    OK(200, "OK"),
    PARTIAL_CONTENT(206, "Partial Content"),
    FOUND(302, "Found"),
    BAD_REQUEST(400, "Bad Request"),
    NOT_FOUND(404, "Not Found"),
    NOT_SATISFIABLE(416, "Not Satisfiable"),
    INTERNAL_SERVER_ERROR(500, "Internal Server Error"),
    NOT_MODIFIED(304, "Not Modified", false),
    MOVED_PERMANENTLY(301, "Moved Permanently") {
        override fun invoke(headers: Headers, body: AsyncHttpMessageBody?, sent: AsyncHttpMessageCompletion?): AsyncHttpResponse {
            if (!headers.contains("Location"))
                throw IllegalArgumentException("The Location header is missing. Use StatusCode.movedPermanently or specify one in the headers argument.")
            return super.invoke(headers, body, sent)
        }
    };

    open operator fun invoke(headers: Headers = Headers(), body: AsyncHttpMessageBody? = null, sent: AsyncHttpMessageCompletion? = null): AsyncHttpResponse {
        return AsyncHttpResponse(ResponseLine(code, message), headers, body, sent)
    }

    companion object {
        fun movedPermanently(location: String, headers: Headers = Headers(), body: AsyncHttpMessageBody? = null, sent: AsyncHttpMessageCompletion? = null): AsyncHttpResponse {
            headers["Location"] = location
            return MOVED_PERMANENTLY(headers, body, sent)
        }
    }
}
