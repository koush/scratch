package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.parser.readAllString
import com.koushikdutta.scratch.tls.createSelfSignedCertificate
import com.koushikdutta.scratch.tls.initializeSSLContext
import com.koushikdutta.scratch.tls.tlsHandshake
import org.junit.Test
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

class TlsTests {
    @Test
    fun testCertificate() {
        val keypairCert = createSelfSignedCertificate("TestServer")

        val pair = createSocketPair()


        async {
            val serverContext = SSLContext.getInstance("TLS")
            initializeSSLContext(serverContext, keypairCert.first, keypairCert.second)

            val engine = serverContext.createSSLEngine()
            engine.useClientMode = false

            val server = tlsHandshake(pair.first, engine)
            server.write(ByteBufferList().putString("Hello World"))
            server.close()
        }

        var data = ""
        async {

            val clientContext = SSLContext.getInstance("TLS")
            initializeSSLContext(clientContext, keypairCert.second)

            val engine = clientContext.createSSLEngine("TestServer", 80)
            engine.useClientMode = true

            val client = tlsHandshake(pair.second, engine)
            data = readAllString(client::read)
        }

        assert(data == "Hello World")
    }

    @Test
    fun testCertificateNameMismatch() {
        val keypairCert = createSelfSignedCertificate("TestServer")

        val pair = createSocketPair()

        try {
            async {
                val serverContext = SSLContext.getInstance("TLS")
                initializeSSLContext(serverContext, keypairCert.first, keypairCert.second)

                val engine = serverContext.createSSLEngine()
                engine.useClientMode = false

                val server = tlsHandshake(pair.first, engine)
                server.write(ByteBufferList().putString("Hello World"))
                server.close()
            }

            var data = ""
            async {

                val clientContext = SSLContext.getInstance("TLS")
                initializeSSLContext(clientContext, keypairCert.second)

                val engine = clientContext.createSSLEngine("BadServerName", 80)
                engine.useClientMode = true

                val client = tlsHandshake(pair.second, engine)
                data = readAllString(client::read)
            }
        }
        catch (exception: SSLException) {
            assert(exception.message!!.contains("hostname verification failed"))
            return
        }
        assert(false)
    }


    @Test
    fun testCertificateTrustFailure() {
        val keypairCert = createSelfSignedCertificate("TestServer")

        val pair = createSocketPair()

        try {
            async {
                val serverContext = SSLContext.getInstance("TLS")
                initializeSSLContext(serverContext, keypairCert.first, keypairCert.second)

                val engine = serverContext.createSSLEngine()
                engine.useClientMode = false

                val server = tlsHandshake(pair.first, engine)
                server.write(ByteBufferList().putString("Hello World"))
                server.close()
            }

            var data = ""
            async {

                val engine = SSLContext.getDefault().createSSLEngine("BadServerName", 80)
                engine.useClientMode = true

                val client = tlsHandshake(pair.second, engine)
                data = readAllString(client::read)
            }
        }
        catch (exception: SSLHandshakeException) {
            return
        }
        assert(false)
    }
}