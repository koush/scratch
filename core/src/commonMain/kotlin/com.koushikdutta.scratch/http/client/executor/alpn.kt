package com.koushikdutta.scratch.http.client.executor

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.async.async
import com.koushikdutta.scratch.async.launch
import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.AsyncHttpResponse
import com.koushikdutta.scratch.http.http2.Http2Connection
import com.koushikdutta.scratch.http.http2.Http2ConnectionMode
import com.koushikdutta.scratch.http.http2.connect
import com.koushikdutta.scratch.http.http2.okhttp.Http2ExchangeCodec
import com.koushikdutta.scratch.http.http2.okhttp.Protocol

interface AlpnSocket: AsyncSocket {
    val negotiatedProtocol: String?
}
typealias AsyncHttpConnectAlpnSocket = suspend () -> AlpnSocket

class Http2ConnectionExecutor(val http2Connection: Http2Connection): AsyncHttpClientExecutor {
    override val affinity: AsyncAffinity = http2Connection.socket

    override suspend fun invoke(request: AsyncHttpRequest): AsyncHttpResponse {
        val socket = http2Connection.connect(request)
        val response = Http2ExchangeCodec.createResponse(socket.readHeaders(), socket)
        if (socket.pushPromise)
            response.headers["X-Scratch-PushPromise"] = "true"
        return response
    }
}

class AsyncHttpAlpnExecutor(override val affinity: AsyncAffinity = AsyncAffinity.NO_AFFINITY, private val connect: AsyncHttpConnectAlpnSocket): AsyncHttpClientExecutor {
    var socketExecutor: AsyncHttpSocketExecutor? = null
    var http2Executor: Http2ConnectionExecutor? = null

    override suspend fun invoke(request: AsyncHttpRequest): AsyncHttpResponse {
        affinity.await()

        if (http2Executor == null) {
            if (socketExecutor != null && socketExecutor!!.isAlive)
                return socketExecutor!!(request)
            socketExecutor = null

            val socket = connect()
            if (socket.negotiatedProtocol != Protocol.HTTP_2.protocol) {
                socketExecutor = AsyncHttpSocketExecutor(socket)
                return socketExecutor!!(request)
            }

            val connection = Http2Connection.upgradeHttp2Connection(socket, Http2ConnectionMode.Client)
            // do not accept incoming connections (but push promises are accepted)
            affinity.launch {
                try {
                    connection.acceptAsync {
                        close()
                    }
                    .awaitClose()
                }
                finally {
                    http2Executor = null
                }
            }

            http2Executor = Http2ConnectionExecutor(connection)
        }

        val response = http2Executor!!(request)
        response.headers["X-Scratch-ALPN"] = Protocol.HTTP_2.protocol
        return response
    }
}
