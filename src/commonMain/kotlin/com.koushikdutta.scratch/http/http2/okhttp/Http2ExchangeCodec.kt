package com.koushikdutta.scratch.http.http2.okhttp

import com.koushikdutta.scratch.AsyncRead
import com.koushikdutta.scratch.http.*
import com.koushikdutta.scratch.http.http2.Http2Stream
import com.koushikdutta.scratch.uri.URI

class Http2ExchangeCodec {
    companion object {
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

        fun createResponse(stream: Http2Stream): AsyncHttpResponse {
            val outHeaders = Headers()
            var statusLine: String? = null

            for (header in stream.headers!!) {
                val name = header.name.string
                val value = header.value.string
                if (name == Header.RESPONSE_STATUS_UTF8) {
                    statusLine = "HTTP/1.1 $value"
                } else if (name !in HTTP_2_SKIPPED_RESPONSE_HEADERS) {
                    outHeaders.add(name, value)
                }
            }
            if (statusLine == null) throw Exception("Expected ':status' header not present")

            return AsyncHttpResponse(ResponseLine(statusLine), outHeaders, {stream.read(it)})
        }

        private fun createHeaders(message: AsyncHttpMessage, headerList: MutableList<Header>) {
            for (header in message.headers) {
                // header names must be lowercase.
                val name = header.name.toLowerCase()
                if (name !in HTTP_2_SKIPPED_REQUEST_HEADERS || name == TE && header.value == "trailers") {
                    headerList.add(Header(name, header.value))
                }
            }
        }

        fun createRequestHeaders(request: AsyncHttpRequest): List<Header> {
            val headerList = mutableListOf<Header>()
            headerList.add(Header(Header.TARGET_METHOD, request.method))
            headerList.add(Header(Header.TARGET_PATH, request.requestLinePathAndQuery))
            val host = request.headers.get("Host")
            if (host != null) {
                headerList.add(Header(Header.TARGET_AUTHORITY, host)) // Optional.
            }
            if (request.uri.scheme != null)
                headerList.add(Header(Header.TARGET_SCHEME, request.uri.scheme!!))

            createHeaders(request, headerList)

            return headerList
        }

        fun createResponseHeaders(response: AsyncHttpResponse): List<Header> {
            val headerList = mutableListOf<Header>()
            headerList.add(Header(Header.RESPONSE_STATUS, "${response.code} ${response.message}"))
            createHeaders(response, headerList)
            return headerList
        }

        fun createRequest(headerList: List<Header>, body: AsyncRead?): AsyncHttpRequest {
            val headers = Headers()
            for (header in headerList) {
                headers.add(header.name.string, header.value.string)
            }

            val method = headers.get(Header.TARGET_METHOD_UTF8)
            val path = headers.get(Header.TARGET_PATH_UTF8)!!
            val host = headers.get(Header.TARGET_AUTHORITY_UTF8)
            val scheme = headers.get(Header.TARGET_SCHEME_UTF8)

            headers.set("Host", host)

            headers.remove(Header.TARGET_METHOD_UTF8)
            headers.remove(Header.TARGET_PATH_UTF8)
            headers.remove(Header.TARGET_AUTHORITY_UTF8)
            headers.remove(Header.TARGET_SCHEME_UTF8)

            return AsyncHttpRequest(URI.create(path), method!!, headers = headers, body = body)
        }
    }
}