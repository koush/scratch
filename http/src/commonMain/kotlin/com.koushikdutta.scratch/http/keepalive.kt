package com.koushikdutta.scratch.http

import com.koushikdutta.scratch.http.http2.okhttp.Protocol

class KeepAlive {
    companion object {
        fun isKeepAlive(request: AsyncHttpRequest, response: AsyncHttpResponse): Boolean {
            return isKeepAlive(response.protocol, response.headers) && isKeepAlive(request.protocol, request.headers)
        }

        fun isKeepAlive(protocol: String, headers: Headers): Boolean {
            // connection is always keep alive as this is an http/1.1 client
            val connection = headers.get("Connection") ?: return protocol.toLowerCase() == Protocol.HTTP_1_1.toString()
            return "keep-alive".equals(connection, ignoreCase = true)
        }
    }
}