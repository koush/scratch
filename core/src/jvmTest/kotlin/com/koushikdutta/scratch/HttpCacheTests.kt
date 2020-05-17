package com.koushikdutta.scratch

import com.koushikdutta.scratch.async.async
import com.koushikdutta.scratch.http.AsyncHttpResponse
import com.koushikdutta.scratch.http.Headers
import com.koushikdutta.scratch.http.Methods
import com.koushikdutta.scratch.http.StatusCode
import com.koushikdutta.scratch.http.body.Utf8StringBody
import com.koushikdutta.scratch.http.client.*
import com.koushikdutta.scratch.http.client.middleware.AsyncHttpClientMiddleware
import com.koushikdutta.scratch.http.client.middleware.useCache
import com.koushikdutta.scratch.http.server.AsyncHttpServer
import com.koushikdutta.scratch.parser.readAllString
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HttpCacheTests {
    fun testHandlerExpecting(expecting: AsyncHttpResponse.() -> Unit, callback: AsyncHttpExecutor) {
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
                session.transport = AsyncHttpClientTransport(serverSocket.connect())
                return true
            }
        })

        val result = client.eventLoop.async {
            val get = Methods.GET("http://example.com")
            val data = readAllString(client.execute(get).body!!)
            
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
        val response = StatusCode.OK(body = Utf8StringBody("hello world"))
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
            StatusCode.NOT_MODIFIED()
        }
        else {
            val headers = Headers()
            headers["ETag"] = "hello"
            StatusCode.OK(headers = headers, body = Utf8StringBody("hello world"))
        }
    }

    @Test
    fun testConditionalCacheMismatch() = testHandlerExpecting({
        assertNull(headers["X-Scratch-Cache"])
    }) {
        if (it.headers["If-None-Match"] == "world") {
            StatusCode.NOT_MODIFIED()
        }
        else {
            val headers = Headers()
            headers["ETag"] = "hello"
            StatusCode.OK(headers = headers, body = Utf8StringBody("hello world"))
        }
    }
}