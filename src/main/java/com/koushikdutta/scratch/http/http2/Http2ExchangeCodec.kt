package com.koushikdutta.scratch.http.http2

import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.AsyncHttpResponse
import com.koushikdutta.scratch.http.Headers
import com.koushikdutta.scratch.http.ResponseLine
import java.net.ProtocolException
import java.util.*

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
            if (statusLine == null) throw ProtocolException("Expected ':status' header not present")

            return AsyncHttpResponse(ResponseLine(statusLine), outHeaders, stream::read)
        }

         fun createRequest(request: AsyncHttpRequest): List<Header> {
             val headerList = mutableListOf<Header>()
             headerList.add(Header(Header.TARGET_METHOD, request.method))
             headerList.add(Header(Header.TARGET_PATH, request.requestLinePathAndQuery))
             val host = request.headers.get("Host")
             if (host != null) {
                 headerList.add(Header(Header.TARGET_AUTHORITY, host)) // Optional.
             }
             headerList.add(Header(Header.TARGET_SCHEME, request.uri.scheme))

             for (header in request.headers) {
                 // header names must be lowercase.
                 val name = header.name.toLowerCase(Locale.US)
                 if (name !in HTTP_2_SKIPPED_REQUEST_HEADERS || name == TE && header.value == "trailers") {
                     headerList.add(Header(name, header.value))
                 }
             }
             return headerList
         }
    }
}