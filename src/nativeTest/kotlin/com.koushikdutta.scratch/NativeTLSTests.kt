package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.AsyncHttpResponse
import com.koushikdutta.scratch.http.OK
import com.koushikdutta.scratch.http.POST
import com.koushikdutta.scratch.http.body.Utf8StringBody
import com.koushikdutta.scratch.http.client.AsyncHttpClient
import com.koushikdutta.scratch.http.client.AsyncHttpClientSession
import com.koushikdutta.scratch.http.client.middleware.OpenSSLMiddleware
import com.koushikdutta.scratch.http.server.AsyncHttpServer
import com.koushikdutta.scratch.parser.readAllString
import com.koushikdutta.scratch.tls.*
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeTLSTests {
    @Test
    fun testHttp2Alpn() {
        val keypairCert = createSelfSignedCertificate("TestServer")

        val serverContext = createTLSContext()
        serverContext.init(keypairCert.first, keypairCert.second)


        val server = createAsyncPipeServerSocket()
        val tlsServer = server.listenTls {
            val engine = serverContext.createSSLEngine()
            engine.setAlpnProtocols(listOf("h2", "http/1.1"))
            engine
        }

        val clientContext = createTLSContext()
        clientContext.init(keypairCert.second)

        var protocol = ""
        val pipeMiddleware = object : OpenSSLMiddleware(clientContext) {
            override suspend fun wrapTlsSocket(
                session: AsyncHttpClientSession,
                tlsSocket: AsyncTlsSocket,
                host: String,
                port: Int
            ): AsyncSocket {
                protocol = tlsSocket.engine.getNegotiatedAlpnProtocol()!!
                return super.wrapTlsSocket(session, tlsSocket, host, port)
            }

            override suspend fun connectInternal(
                session: AsyncHttpClientSession,
                host: String,
                port: Int
            ): AsyncSocket {
                return server.connect()
            }
        }

        val httpServer = AsyncHttpServer {
            // would be cool to pipe hte request right back to the response
            // without buffering, but the http spec does not work that way.
            // entire request must be received before sending a response.
            val data = readAllString(it.body!!)
            assert(data == "hello world")
            AsyncHttpResponse.OK(body = Utf8StringBody(data))
        }

        httpServer.listen(tlsServer)

        var data = ""
        async {
            val client = AsyncHttpClient()
            client.middlewares.add(0, pipeMiddleware)
            val connection =
                client.execute(AsyncHttpRequest.POST("https://TestServer", body = Utf8StringBody("hello world")))
            data = readAllString(connection.body!!)
        }

        assertEquals(data, "hello world")
        assertEquals(protocol, "h2")
    }
}