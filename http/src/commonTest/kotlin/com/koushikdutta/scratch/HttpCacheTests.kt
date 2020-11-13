package com.koushikdutta.scratch

import com.koushikdutta.scratch.async.async
import com.koushikdutta.scratch.event.AsyncEventLoop
import com.koushikdutta.scratch.http.AsyncHttpResponse
import com.koushikdutta.scratch.http.Headers
import com.koushikdutta.scratch.http.Methods
import com.koushikdutta.scratch.http.StatusCode
import com.koushikdutta.scratch.http.body.Utf8StringBody
import com.koushikdutta.scratch.http.client.AsyncHttpClient
import com.koushikdutta.scratch.http.client.AsyncHttpExecutor
import com.koushikdutta.scratch.http.client.buildUpon
import com.koushikdutta.scratch.http.client.executor.AsyncHttpClientExecutor
import com.koushikdutta.scratch.http.client.executor.useMemoryCache
import com.koushikdutta.scratch.http.client.get
import com.koushikdutta.scratch.parser.readAllString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

open class HttpCacheTests {
    open fun createClient(loop: AsyncEventLoop, callback: AsyncHttpExecutor): AsyncHttpClientExecutor {
        val client = AsyncHttpClient(loop)
        client.schemeExecutor.unhandled = callback
        return client
            .buildUpon()
            .useMemoryCache()
            .build()
    }

    fun testHandlerExpecting(expecting: AsyncHttpResponse.() -> Unit, callback: AsyncHttpExecutor) {
        val loop = AsyncEventLoop()
        val client = createClient(loop, callback)

        val result = loop.async {
            val get = Methods.GET("/")
            val data = readAllString(client(get).body!!)

            val data2 = client.get("/") {
                expecting(it)
                readAllString(it.body!!)
            }

            assertEquals(data, data2)
        }
        .asPromise()

        result.finally { loop.stop() }

        loop.run()
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
        assertEquals(headers["X-Scratch-Cache"], "ConditionalCache")
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