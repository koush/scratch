package com.koushikdutta.scratch

import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.AsyncHttpResponse
import com.koushikdutta.scratch.http.GET
import com.koushikdutta.scratch.http.OK
import com.koushikdutta.scratch.http.body.StringBody
import com.koushikdutta.scratch.http.client.AsyncHttpClient
import com.koushikdutta.scratch.http.server.AsyncHttpServer
import com.koushikdutta.scratch.parser.readAllString
import com.koushikdutta.scratch.tls.connectTls
import com.koushikdutta.scratch.tls.createSelfSignedCertificate
import com.koushikdutta.scratch.tls.initializeSSLContext
import com.koushikdutta.scratch.tls.listenTls
import org.junit.Test
import java.net.URI
import javax.net.ssl.SSLContext


class HttpTests {
    @Test
    fun testHttp() {
        val pair = createAsyncPipeSocketPair()

        async {
            val httpServer = AsyncHttpServer {
                AsyncHttpResponse.OK(body = StringBody("hello world"))
            }

            httpServer.accept(pair.second)
        }


        var requestsCompleted = 0
        async {
            val httpClient = AsyncHttpClient()
            val reader = AsyncReader(pair.first::read)

            for (i in 1..3) {
                val result = httpClient.execute(AsyncHttpRequest.GET("http://example/foo"), pair.first, reader)
                val data = readAllString(result.body!!)
                assert(data == "hello world")
                requestsCompleted++
            }
        }

        assert(requestsCompleted == 3)
    }

    @Test
    fun testHttpEcho() {
        val pair = createAsyncPipeSocketPair()

        async {
            val httpServer = AsyncHttpServer {
                // would be cool to pipe hte request right back to the response
                // without buffering, but the http spec does not work that way.
                // entire request must be received before sending a response.
                val data = readAllString(it.body!!)
                assert(data == "hello world")
                AsyncHttpResponse.OK(body = StringBody(data))
            }

            httpServer.accept(pair.second)
        }

        var requestsCompleted = 0
        async {
            val httpClient = AsyncHttpClient()
            val reader = AsyncReader(pair.first::read)

            for (i in 1..3) {
                val request = AsyncHttpRequest(URI.create("http://example/foo"), "POST", body = StringBody("hello world"))
                val result = httpClient.execute(request, pair.first, reader)
                val data = readAllString(result.body!!)
                assert(data == "hello world")
                requestsCompleted++
            }
        }

        assert(requestsCompleted == 3)
    }


    @Test
    fun testHttpPipeServer() {
        val server = createAsyncPipeServerSocket()
        val httpServer = AsyncHttpServer {
            // would be cool to pipe hte request right back to the response
            // without buffering, but the http spec does not work that way.
            // entire request must be received before sending a response.
            val data = readAllString(it.body!!)
            assert(data == "hello world")
            AsyncHttpResponse.OK(body = StringBody(data))
        }

        httpServer.listen(server)

        var requestsCompleted = 0
        async {
            val httpClient = AsyncHttpClient()
            val socket = server.connect()
            val reader = AsyncReader(socket::read)

            for (i in 1..3) {
                val request = AsyncHttpRequest(URI.create("http://example/foo"), "POST", body = StringBody("hello world"))
                val result = httpClient.execute(request, socket, reader)
                val data = readAllString(result.body!!)
                assert(data == "hello world")
                requestsCompleted++
            }
        }

        assert(requestsCompleted == 3)
    }


    @Test
    fun testHttpsPipeServer() {
        val keypairCert = createSelfSignedCertificate("TestServer")
        val serverContext = SSLContext.getInstance("TLS")
        initializeSSLContext(serverContext, keypairCert.first, keypairCert.second)

        val server = createAsyncPipeServerSocket()
        val tlsServer = server.listenTls(serverContext)
        val httpServer = AsyncHttpServer {
            // would be cool to pipe hte request right back to the response
            // without buffering, but the http spec does not work that way.
            // entire request must be received before sending a response.
            val data = readAllString(it.body!!)
            assert(data == "hello world")
            AsyncHttpResponse.OK(body = StringBody(data))
        }

        httpServer.listen(tlsServer)

        var requestsCompleted = 0
        async {
            val clientContext = SSLContext.getInstance("TLS")
            initializeSSLContext(clientContext, keypairCert.second)

            val httpClient = AsyncHttpClient()
            val socket = server.connect().connectTls("TestServer", 80, clientContext)
            val reader = AsyncReader(socket::read)

            for (i in 1..3) {
                val request = AsyncHttpRequest(URI.create("http://example/foo"), "POST", body = StringBody("hello world"))
                val result = httpClient.execute(request, socket, reader)
                val data = readAllString(result.body!!)
                assert(data == "hello world")
                requestsCompleted++
            }
        }

        assert(requestsCompleted == 3)
    }
}
