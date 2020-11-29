package com.koushikdutta.scratch.http

import com.koushikdutta.scratch.AsyncRead
import com.koushikdutta.scratch.AsyncSocket

private const val HTTP_1_1 = "HTTP/1.1"

internal class AsyncHttpResponseSwitchingProtocols(headers: Headers = Headers(), protocol: String = HTTP_1_1, internal val block: suspend(socket: AsyncSocket) -> Unit)
    : AsyncHttpResponse(ResponseLine(StatusCode.SWITCHING_PROTOCOLS, protocol), headers, null as AsyncRead?, null)

fun AsyncHttpResponse.Companion.SWITCHING_PROTOCOLS(headers: Headers = Headers(), protocol: String = HTTP_1_1, block: suspend(socket: AsyncSocket) -> Unit) : AsyncHttpResponse
    = AsyncHttpResponseSwitchingProtocols(headers, protocol, block)


enum class StatusCode(val code: Int, val message: String, val hasBody: Boolean = true) {
    SWITCHING_PROTOCOLS(101, "Switching Protocols", false),
    OK(200, "OK"),
    NO_CONTENT(204, "No Content", false),
    MULTI_STATUS(207, "Multi-Status"),
    PARTIAL_CONTENT(206, "Partial Content"),
    FOUND(302, "Found") {
        override fun invoke(headers: Headers, body: AsyncRead?, sent: AsyncHttpMessageCompletion?) =
                redirect(headers, body, sent)
    },
    BAD_REQUEST(400, "Bad Request"),
    UNAUTHORIZED(401, "Unauthorized"),
    NOT_FOUND(404, "Not Found"),
    NOT_SATISFIABLE(416, "Not Satisfiable"),
    INTERNAL_SERVER_ERROR(500, "Internal Server Error"),
    NOT_MODIFIED(304, "Not Modified", false),
    MOVED_PERMANENTLY(301, "Moved Permanently") {
        override fun invoke(headers: Headers, body: AsyncRead?, sent: AsyncHttpMessageCompletion?) =
                redirect(headers, body, sent)
    };

    internal fun redirect(headers: Headers, body: AsyncRead?, sent: AsyncHttpMessageCompletion?): AsyncHttpResponse {
        if (!headers.contains("Location"))
            throw IllegalArgumentException("The Location header is missing. Use StatusCode.movedPermanently or StatusCode.found or specify one in the headers argument.")
        return invokeInternal(headers, body, sent)
    }

    private fun invokeInternal(headers: Headers = Headers(), body: AsyncRead? = null, sent: AsyncHttpMessageCompletion? = null): AsyncHttpResponse {
        return AsyncHttpResponse(ResponseLine(code, message), headers, body, sent)
    }

    open operator fun invoke(headers: Headers = Headers(), body: AsyncRead? = null, sent: AsyncHttpMessageCompletion? = null): AsyncHttpResponse {
        return invokeInternal(headers, body, sent)
    }

    open operator fun invoke(headers: Headers = Headers(), body: AsyncHttpMessageContent, sent: AsyncHttpMessageCompletion? = null): AsyncHttpResponse {
        return invoke(headers, body as AsyncRead, AsyncHttpMessageContent.prepare(headers, body, sent))
    }

    companion object {
        fun movedPermanently(location: String, headers: Headers = Headers(), body: AsyncRead? = null, sent: AsyncHttpMessageCompletion? = null): AsyncHttpResponse {
            headers["Location"] = location
            return MOVED_PERMANENTLY(headers, body, sent)
        }
        fun found(location: String, headers: Headers = Headers(), body: AsyncRead? = null, sent: AsyncHttpMessageCompletion? = null): AsyncHttpResponse {
            headers["Location"] = location
            return FOUND(headers, body, sent)
        }
    }
}
