package com.koushikdutta.scratch

import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.StatusCode
import com.koushikdutta.scratch.http.server.AsyncHttpRouter
import com.koushikdutta.scratch.http.server.get
import com.koushikdutta.scratch.http.server.post
import com.koushikdutta.scratch.http.server.put
import com.koushikdutta.scratch.uri.URI
import kotlin.test.Test
import kotlin.test.assertEquals

class RouterTests {
    @Test
    fun testRouter() {
        val router = AsyncHttpRouter()
        val ok1 = StatusCode.OK()
        val ok2 = StatusCode.OK()
        val ok3 = StatusCode.OK()

        router.get("/") {
            ok1
        }
        router.get("/foo") {
            ok2
        }
        router.post("/bar") {
            ok3
        }

        async {
            assertEquals(router(AsyncHttpRequest(URI.create("/"))), ok1)
            assertEquals(router(AsyncHttpRequest(URI.create("/foo"))), ok2)
            assertEquals(router(AsyncHttpRequest(URI.create("/bar"), "POST")), ok3)
            assertEquals(router(AsyncHttpRequest(URI.create("/404"))), null)
        }
    }

    @Test
    fun testRouterMatches() {
        val router = AsyncHttpRouter()
        val ok1 = StatusCode.OK()
        val ok2 = StatusCode.OK()
        val ok3 = StatusCode.OK()

        router.get("/(.*?)/foo") {
            assertEquals(match.groups[0]!!.value, "/test/foo")
            assertEquals(match.groups[1]!!.value, "test")
            ok1
        }
        router.get("/foo/(.*?)") {
            assertEquals(match.groups[0]!!.value, "/foo/bar")
            assertEquals(match.groups[1]!!.value, "bar")
            ok2
        }
        router.put(".*") {
            ok3
        }

        async {
            assertEquals(router(AsyncHttpRequest(URI.create("/test/foo?query=ignored"))), ok1)
            assertEquals(router(AsyncHttpRequest(URI.create("/foo/bar?query"))), ok2)

            assertEquals(router(AsyncHttpRequest(URI.create("/asvasd"), "PUT")), ok3)
            assertEquals(router(AsyncHttpRequest(URI.create("/fofffy"), "PUT")), ok3)
            assertEquals(router(AsyncHttpRequest(URI.create("/asdggg"), "PUT")), ok3)
            assertEquals(router(AsyncHttpRequest(URI.create("/q"), "PUT")), ok3)
        }
    }

}