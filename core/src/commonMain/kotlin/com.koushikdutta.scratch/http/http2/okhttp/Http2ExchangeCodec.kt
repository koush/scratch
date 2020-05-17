package com.koushikdutta.scratch.http.http2.okhttp

import com.koushikdutta.scratch.AsyncRead
import com.koushikdutta.scratch.http.*
import com.koushikdutta.scratch.http.http2.Http2Socket
import com.koushikdutta.scratch.uri.URI

internal class Http2ExchangeCodec {
    internal companion object {
        private const val CONNECTION = "connection"
        private const val HOST = "host"
        private const val KEEP_ALIVE = "keep-alive"
        private const val PROXY_CONNECTION = "proxy-connection"
        private const val TRANSFER_ENCODING = "transfer-encoding"
        private const val TE = "te"
        private const val ENCODING = "encoding"
        private const val UPGRADE = "upgrade"

        /** See http://tools.ietf.org/html/draft-ietf-httpbis-http2-09#section-8.1.3. */
        private val HTTP_2_SKIPPED_REQUEST_HEADERS = listOf(
                CONNECTION,
                HOST,
                KEEP_ALIVE,
                PROXY_CONNECTION,
                TE,
                TRANSFER_ENCODING,
                ENCODING,
                UPGRADE,
                Header.TARGET_METHOD_UTF8,
                Header.TARGET_PATH_UTF8,
                Header.TARGET_SCHEME_UTF8,
                Header.TARGET_AUTHORITY_UTF8)
        private val HTTP_2_SKIPPED_RESPONSE_HEADERS = listOf(
                CONNECTION,
                HOST,
                KEEP_ALIVE,
                PROXY_CONNECTION,
                TE,
                TRANSFER_ENCODING,
                ENCODING,
                UPGRADE)

        fun createResponse(responseHeaders: Headers, socket: Http2Socket): AsyncHttpResponse {
            val outHeaders = Headers()
            var statusLine: String? = null

            for (header in responseHeaders) {
                val name = header.name
                val value = header.value
                if (name == Header.RESPONSE_STATUS_UTF8) {
                    statusLine = "HTTP/1.1 $value"
                } else if (name !in HTTP_2_SKIPPED_RESPONSE_HEADERS) {
                    outHeaders.add(name, value)
                }
            }
            if (statusLine == null) throw Exception("Expected ':status' header not present")

            return AsyncHttpResponse(ResponseLine(statusLine), outHeaders, socket::read) {
                socket.close()
            }
        }

        private fun createHeaders(message: AsyncHttpMessage, headers: Headers) {
            for (header in message.headers) {
                // header names must be lowercase.
                val name = header.name.toLowerCase()
                if (name !in HTTP_2_SKIPPED_REQUEST_HEADERS || name == TE && header.value == "trailers") {
                    headers.add(name, header.value)
                }
            }
        }

        fun createRequestHeaders(request: AsyncHttpRequest): Headers {
            val headers = Headers()
            headers.add(Header.TARGET_METHOD.string, request.method)
            headers.add(Header.TARGET_PATH.string, request.requestLinePathAndQuery)
            val host = request.headers["Host"]
            if (host != null)
                headers.add(Header.TARGET_AUTHORITY.string, host)
            val scheme = request.uri.scheme
            if (scheme != null)
                headers.add(Header.TARGET_SCHEME.string, scheme)

            createHeaders(request, headers)

            return headers
        }

        fun createResponseHeaders(response: AsyncHttpResponse): Headers {
            val headers = Headers()
            headers.add(Header.RESPONSE_STATUS.string, "${response.code} ${response.message}")
            createHeaders(response, headers)
            return headers
        }

        fun createRequest(headers: Headers, body: AsyncRead?): AsyncHttpRequest {
            val method = headers[Header.TARGET_METHOD_UTF8]
            val path = headers[Header.TARGET_PATH_UTF8]!!
            val host = headers[Header.TARGET_AUTHORITY_UTF8]
            val scheme = headers[Header.TARGET_SCHEME_UTF8]

            headers["Host"] = host

            headers.remove(Header.TARGET_METHOD_UTF8)
            headers.remove(Header.TARGET_PATH_UTF8)
            headers.remove(Header.TARGET_AUTHORITY_UTF8)
            headers.remove(Header.TARGET_SCHEME_UTF8)

            return AsyncHttpRequest(URI.create(path), method!!, headers = headers, body = body)
        }
    }
}