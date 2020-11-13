package com.koushikdutta.scratch

import com.koushikdutta.scratch.TestUtils.Companion.networkContextTest
import com.koushikdutta.scratch.http.Headers
import com.koushikdutta.scratch.http.StatusCode
import com.koushikdutta.scratch.http.body.Utf8StringBody
import com.koushikdutta.scratch.http.client.AsyncHttpClient
import com.koushikdutta.scratch.http.client.buildUpon
import com.koushikdutta.scratch.http.client.executor.useHttpsAlpnExecutor
import com.koushikdutta.scratch.http.client.get
import com.koushikdutta.scratch.http.server.AsyncHttpServer
import com.koushikdutta.scratch.parser.readAllString
import kotlin.test.Test
import kotlin.test.assertTrue

class ExternalTests {
    @Test
    fun testGoLangServerPush() = networkContextTest {
        val client = AsyncHttpClient(this)
        client.schemeExecutor.useHttpsAlpnExecutor(client.eventLoop)

        // this will trigger push promise streams
        val html = client.get("https://http2.golang.org/serverpush") {
            readAllString(it.body!!)
        }

//        golang uses cache busting links and scripts, so gotta parse them out
//        /serverpush/static/jquery.min.js?1590000447641406788

        var pushPromiseCount = 0
        val regex = Regex("<link.*?href=\"(.*?)\"")
        for (match in regex.findAll(html)) {
            val href = match.groupValues[1]

            val get = "https://http2.golang.org$href"
            val gotPushPromise = client.get(get) {
                it.headers["X-Scratch-PushPromise"] == "true"
            }
            if (gotPushPromise)
                pushPromiseCount++
        }

        assertTrue(pushPromiseCount > 0)
    }

//    @Test
    fun testInfiniteServer() = networkContextTest {
        val server = AsyncHttpServer {
            val headers = Headers()
            headers["Connection"] = "close"
            StatusCode.OK(headers, body = Utf8StringBody("hello world"))
        }

        server.listen(listen(5454, backlog = 10000))
                .awaitClose()
    }
}