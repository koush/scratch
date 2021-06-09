package com.koushikdutta.scratch

import com.koushikdutta.scratch.http.Headers
import com.koushikdutta.scratch.http.Methods
import com.koushikdutta.scratch.http.StatusCode
import com.koushikdutta.scratch.http.body.Utf8StringBody
import com.koushikdutta.scratch.http.client.executor.AsyncHttpConnectSocketExecutor
import com.koushikdutta.scratch.http.client.executor.AsyncHttpSocketExecutor
import com.koushikdutta.scratch.http.server.AsyncHttpServer
import com.koushikdutta.scratch.parser.readAllString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpExecutorTests {
    @Test
    fun testHttpExecutor() {
        val pipe = createAsyncPipeSocketPair()
        var data = ""
        async {
            val executor = AsyncHttpSocketExecutor(pipe.first)
            data = readAllString(executor(Methods.GET("/", body = Utf8StringBody("beep boop"))).body!!)
            assertTrue(executor.isAlive)
            assertTrue(executor.isResponseEnded)
        }

        async {
            val server = AsyncHttpServer {
                assertEquals("beep boop", readAllString(it.body!!))
                StatusCode.OK(body = Utf8StringBody("hello world"))
            }

            server.accept(pipe.second)
        }

        assertEquals(data, "hello world")
    }

    @Test
    fun testHttpConnectExecutor() {
        val pipeServer = AsyncPipeServerSocket()
        var data = ""
        val executor = AsyncHttpConnectSocketExecutor {
            pipeServer.connect()
        }

        var done = false
        async {
            data = readAllString(executor(Methods.GET("/", body = Utf8StringBody("beep boop"))).body!!)
            assertEquals(data, "hello world")
            data = readAllString(executor(Methods.GET("/", body = Utf8StringBody("beep boop"))).body!!)
            assertEquals(data, "hello world")
            data = readAllString(executor(Methods.GET("/", body = Utf8StringBody("beep boop"))).body!!)
            assertEquals(data, "hello world")
            data = readAllString(executor(Methods.GET("/", body = Utf8StringBody("beep boop"))).body!!)
            done = true
        }

        async {
            val server = AsyncHttpServer {
                assertEquals("beep boop", readAllString(it.body!!))
                StatusCode.OK(body = Utf8StringBody("hello world"))
            }

            server.listenAsync(pipeServer)
        }

        assertEquals(data, "hello world")
        assertEquals(executor.keepaliveSocketSize, 1)
        assertEquals(executor.reusedSocketCount, 3)
        assertTrue(done)

        async {
            // this will close the 1 keepalive socket (incrementing reuse one last time), and force opening new ones
            for (i in 0 until 5) {
                val headers = Headers()
                headers["Connection"] = "close"
                data = readAllString(executor(Methods.GET("/", body = Utf8StringBody("beep boop"), headers = headers)).body!!)
                assertEquals(data, "hello world")
            }
        }

        assertEquals(executor.keepaliveSocketSize, 0)
        // due to coroutine execution order, you'd think this would be 4, but it's actually 3
        // the first socket gets recycled after the second connection
        assertEquals(executor.reusedSocketCount, 3)
    }
}