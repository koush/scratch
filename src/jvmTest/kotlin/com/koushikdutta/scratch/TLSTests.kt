package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.AsyncHttpResponse
import com.koushikdutta.scratch.http.OK
import com.koushikdutta.scratch.http.POST
import com.koushikdutta.scratch.http.body.Utf8StringBody
import com.koushikdutta.scratch.http.client.AsyncHttpClient
import com.koushikdutta.scratch.http.client.AsyncHttpClientSession
import com.koushikdutta.scratch.http.client.middleware.ConscryptMiddleware
import com.koushikdutta.scratch.http.server.AsyncHttpServer
import com.koushikdutta.scratch.parser.readAllString
import com.koushikdutta.scratch.tls.*
import org.conscrypt.Conscrypt
import org.junit.Test
import javax.net.ssl.SSLContext
import kotlin.test.assertEquals

class TLSTests {

    @Test
    fun testConscryptTlsServer() {
        val conscrypt = Conscrypt.newProvider()
//        Security.insertProviderAt(conscrypt, 1)

        val keypairCert = createSelfSignedCertificate("TestServer")
        val serverContext = SSLContext.getInstance("TLS", conscrypt)
        serverContext.init(keypairCert.first, keypairCert.second)

        val server = createAsyncPipeServerSocket()
        val tlsServer = server.listenTls {
            serverContext.createSSLEngine("TestServer", 80)
        }


        var data = ""
        tlsServer.accept().receive {
            data += readAllString(::read)
        }

        for (i in 1..2) {
            async {
                val clientContext = SSLContext.getInstance("TLS", conscrypt)
                clientContext.init(keypairCert.second)

                val client = server.connect().connectTls("TestServer", 80, clientContext)
                client.write(ByteBufferList().putUtf8String("hello world"))
                client.close()
            }
        }

        assertEquals(data, "hello worldhello world")
    }

    @Test
    fun testHttp2Alpn() {
        val conscrypt = Conscrypt.newProvider()
        val keypairCert = createSelfSignedCertificate("TestServer")

        val serverContext = SSLContext.getInstance("TLS", conscrypt)
        serverContext.init(keypairCert.first, keypairCert.second)


        val server = createAsyncPipeServerSocket()
        val tlsServer = server.listenTls {
            val engine = serverContext.createSSLEngine()
            Conscrypt.setApplicationProtocols(engine, arrayOf("h2", "http/1.1"))
            engine
        }

        val clientContext = SSLContext.getInstance("TLS", conscrypt)
        clientContext.init(keypairCert.second)

        var protocol = ""
        val pipeMiddleware = object : ConscryptMiddleware(clientContext) {
            override suspend fun wrapTlsSocket(
                session: AsyncHttpClientSession,
                tlsSocket: AsyncTlsSocket,
                host: String,
                port: Int
            ): AsyncSocket {
                protocol = Conscrypt.getApplicationProtocol(tlsSocket.engine)
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
            pipeMiddleware.install(client)
            val connection =
                client.execute(AsyncHttpRequest.POST("https://TestServer", body = Utf8StringBody("hello world")))
            data = readAllString(connection.body!!)
        }

        assert(data == "hello world")
        assert(protocol == "h2")
    }

}