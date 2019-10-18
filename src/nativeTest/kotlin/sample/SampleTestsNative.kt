package sample

import com.koushikdutta.scratch.async
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.createAsyncPipeSocketPair
import com.koushikdutta.scratch.crypto.*
import com.koushikdutta.scratch.event.AsyncEventLoop
import com.koushikdutta.scratch.event.UvEventLoop.Companion.getInterfaceAddresses
import com.koushikdutta.scratch.event.getByName
import com.koushikdutta.scratch.http.AsyncHttpResponse
import com.koushikdutta.scratch.http.Headers
import com.koushikdutta.scratch.http.OK
import com.koushikdutta.scratch.http.body.Utf8StringBody
import com.koushikdutta.scratch.http.client.AsyncHttpClient
import com.koushikdutta.scratch.http.server.AsyncHttpServer
import com.koushikdutta.scratch.parser.readAllString
import com.koushikdutta.scratch.tls.*
import kotlinx.cinterop.invoke
import platform.posix.sleep
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertTrue

private class TimeoutException : Exception()
private class ExpectedException: Exception()

class SampleTestsNative {
//     @Test
//     fun testHello() {
//         assertTrue("Native" in hello())
//     }
//
    private fun networkContextTest(failureExpected: Boolean = false, runner: suspend AsyncEventLoop.() -> Unit) {
        val networkContext = AsyncEventLoop()

        val result = networkContext.async {
            try {
                runner(networkContext)
            }
            finally {
                networkContext.stop()
            }
        }

        networkContext.postDelayed(100000000) {
            result.setComplete(Result.failure(TimeoutException()))
            networkContext.stop()
        }

        try {
            networkContext.run()
            result.rethrow()
            assert(!failureExpected)
        }
        catch (exception: ExpectedException) {
            assert(failureExpected)
        }
    }
//
//    @Test
//    fun testHttpServer() = networkContextTest {
//        val http = AsyncHttpServer {
//            AsyncHttpResponse.OK(Headers(), Utf8StringBody("hello!"))
//        }
//        val socket = listen(0)
//        println(socket.localPort)
//        http.listen(socket)
//
//        suspendCoroutine<Unit> {  }
//    }
//
//    companion object {
//        // seem to need to reference this class to trigger the static constructor. wierd.
//        fun doInit() {
//        }
//        init {
//            OPENSSL_init_ssl(0, null)
//            ERR_load_SSL_strings()
//            ERR_load_BIO_strings()
//            ERR_load_CRYPTO_strings()
//        }
//    }
//
    @Test
    fun testSSL() = networkContextTest {
        val keypairCert = createSelfSignedCertificate("TestServer")
        val serverContext = createTLSContext()
        serverContext.init(keypairCert.first, keypairCert.second)
        val server = listen()
        val tlsServer = server.listenTls(serverContext)

        println(server.localPort)
        for (socket in server.accept()) {

        }
    }

//    @Test
//    fun testCertificate() {
//        val keypairCert = createSelfSignedCertificate("TestServer")
//
//        val pair = createAsyncPipeSocketPair()
//
//
//        async {
//            val serverContext = createTLSContext()
//            serverContext.init(keypairCert.first, keypairCert.second)
//
//            val engine = serverContext.createSSLEngine()
//            engine.useClientMode = false
//
//            val server = tlsHandshake(pair.first, engine)
//            server.write(ByteBufferList().putUtf8String("Hello World"))
//            server.close()
//        }
//
//        var data = ""
//        async {
//            val clientContext = createTLSContext()
//            clientContext.init(keypairCert.second)
//
//            val engine = clientContext.createSSLEngine("TestServer", 80)
//            engine.useClientMode = true
//
//            val client = tlsHandshake(pair.second, engine)
//            data = readAllString({client.read(it)})
//        }
//
//        println(data)
//        assert(data == "Hello World")
//    }
//
//    @Test
//    fun testGoogle() = networkContextTest {
//        createSelfSignedCertificate("test")
//
//
//        val secureSocket = connectTls("google.com", 443)
//
//        async {
//            while (true) {
//                val buffer = ByteBufferList()
//                buffer.putUtf8String("GET / HTTP/1.1\r\n\r\n")
//                secureSocket.write(buffer)
//                sleep(5000)
//            }
//        }
//
//        val buffer = ByteBufferList()
//        while (secureSocket.read(buffer)) {
//            println(buffer.readUtf8String())
//        }
////        PEM_write_bio_PrivateKey()
//    }
}