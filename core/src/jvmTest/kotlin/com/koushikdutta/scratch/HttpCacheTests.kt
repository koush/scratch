package com.koushikdutta.scratch

import buildUpon
import com.koushikdutta.scratch.async.async
import com.koushikdutta.scratch.http.*
import com.koushikdutta.scratch.http.body.Utf8StringBody
import com.koushikdutta.scratch.http.client.AsyncHttpClient
import com.koushikdutta.scratch.http.client.AsyncHttpClientSession
import com.koushikdutta.scratch.http.client.middleware.AsyncHttpClientMiddleware
import com.koushikdutta.scratch.http.client.middleware.useCache
import com.koushikdutta.scratch.http.http2.okhttp.Protocol
import com.koushikdutta.scratch.http.server.AsyncHttpRequestHandler
import com.koushikdutta.scratch.http.server.AsyncHttpServer
import com.koushikdutta.scratch.parser.readAllString
import get
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import execute

class HttpCacheTests {
    fun testHandlerExpecting(expecting: AsyncHttpResponse.() -> Unit, callback: AsyncHttpRequestHandler) {
        val client = AsyncHttpClient()
                .buildUpon()
                .useCache()
                .build()

        client.client.middlewares.add(0, object : AsyncHttpClientMiddleware() {
            val server = AsyncHttpServer(callback)

            val serverSocket = AsyncPipeServerSocket()

            init {
                server.listen(serverSocket)
            }

            override suspend fun connectSocket(session: AsyncHttpClientSession): Boolean {
                session.socket = serverSocket.connect()
                session.protocol = Protocol.HTTP_1_0.toString()
                session.socketReader = AsyncReader(session.socket!!::read)
                return true
            }
        })

        val result = client.eventLoop.async {
            val get = AsyncHttpRequest.GET("http://example.com")
            val data = readAllString(client.execute(get).body!!)

            println(data)

            val data2 = client.get("http://example.com") {
                expecting(it)
                readAllString(it.body!!)
            }

            assertEquals(data, data2)
        }

        result.finally { client.eventLoop.stop() }

        client.eventLoop.run()
        result.getOrThrow()
    }

    fun testHeadersExpecting(callback: AsyncHttpResponse.() -> Unit, expecting: AsyncHttpResponse.() -> Unit) = testHandlerExpecting(expecting) {
        val response = AsyncHttpResponse.OK(body = Utf8StringBody("hello world"))
        callback(response)
        response
    }

    fun testHeadersExpecting(callback: AsyncHttpResponse.() -> Unit) = testHeadersExpecting(callback) {
        assertEquals(headers["X-Scratch-Cache"], "Cache")
    }

    fun testHeadersExpectingNotCached(callback: AsyncHttpResponse.() -> Unit) = testHeadersExpecting(callback) {
        assertNull(headers["X-Scratch-Cache"])
    }

    @Test
    fun testCacheImmutable() = testHeadersExpecting {
        headers["Cache-Control"] = "immutable"
    }

    @Test
    fun testCacheMaxAge() = testHeadersExpecting {
        headers["Cache-Control"] = "max-age=300"
    }

    @Test
    fun testCacheMaxAgeExpired() = testHeadersExpectingNotCached {
        headers["Cache-Control"] = "max-age=0"
    }

    @Test
    fun testConditionalCache() = testHandlerExpecting({
        assertEquals(headers["X-Scratch-Cache"], "Cache")
    }) {
        if (it.headers["If-None-Match"] == "hello") {
            AsyncHttpResponse.NOT_MODIFIED()
        }
        else {
            val headers = Headers()
            headers["ETag"] = "hello"
            AsyncHttpResponse.OK(headers = headers, body = Utf8StringBody("hello world"))
        }
    }
}