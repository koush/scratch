package com.koushikdutta.scratch

import com.koushikdutta.scratch.TestUtils.Companion.networkContextTest
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.http.Methods
import com.koushikdutta.scratch.http.StatusCode
import com.koushikdutta.scratch.http.body.Utf8StringBody
import com.koushikdutta.scratch.http.client.AsyncHttpClient
import com.koushikdutta.scratch.http.client.execute
import com.koushikdutta.scratch.http.server.AsyncHttpServer
import com.koushikdutta.scratch.parser.parse
import com.koushikdutta.scratch.parser.readString
import kotlin.test.Test

class ReadmeTests {
//    @Test
    fun testEcho() = networkContextTest {
        val server = listen(5555)
        // this will accept sockets one at a time
        // and echo data back
        for (socket in server.accept()) {
            val buffer = ByteBufferList()
            while (socket.read(buffer)) {
                socket.write(buffer)
            }
        }
    }

//    @Test
    fun testHttpEcho() = networkContextTest {
        val server = AsyncHttpServer {
            val body = it.parse().readString()
            StatusCode.OK(body = Utf8StringBody(body))
        }
        server.listen(5555).await()

        val client = AsyncHttpClient()
        val response = client(Methods.GET("http://localhost:5555", body = Utf8StringBody("hello world")))
        println("from server: " + response.parse().readString())
    }
}
