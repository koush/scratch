package com.koushikdutta.scratch

import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.AsyncHttpResponse
import com.koushikdutta.scratch.http.GET
import com.koushikdutta.scratch.http.OK
import com.koushikdutta.scratch.http.body.StringBody
import com.koushikdutta.scratch.http.http2.Http2Connection
import com.koushikdutta.scratch.parser.readAllString
import org.junit.Test

class Http2Tests {
    @Test
    fun testConnection() {
        val pair = createSocketPair()

        async {
            val server = Http2Connection(pair.second, false) {
                AsyncHttpResponse.OK(body = StringBody("Hello World"))
            }
        }

        var data = ""
        async {
            val client = Http2Connection(pair.first, true)
            val connected = client.newStream(AsyncHttpRequest.GET("https://example.com/"), true)
            data = readAllString(connected::read)
        }

        assert(data == "Hello World")
    }
}
