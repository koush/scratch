package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.AsyncHttpResponse
import com.koushikdutta.scratch.http.OK
import com.koushikdutta.scratch.http.body.StringBody
import com.koushikdutta.scratch.http.client.AsyncHttpClient
import com.koushikdutta.scratch.http.client.middleware.ConscryptMiddleware
import com.koushikdutta.scratch.http.server.AsyncHttpServer
import com.koushikdutta.scratch.net.AsyncNetworkContext
import com.koushikdutta.scratch.tls.connectTls
import com.koushikdutta.scratch.tls.tlsHandshake
import org.conscrypt.Conscrypt
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.security.Security
import javax.net.ssl.SSLContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class Main {
    companion object {
        fun asyncWriterTest() {
            val reader = asyncWriter {
                var buffer = ByteBufferList()
                buffer.putString("test")
                write(buffer)

                buffer.putString("test2")
                write(buffer)

                throw IOException("whoops")
            }

            async {
                val buffer = ByteBufferList()
                try {
                    reader(buffer)
                    println(buffer.getString())
                    reader(buffer)
                    println(buffer.getString())
                    reader(buffer)
                    println(buffer.getString())
                }
                catch (e: Exception) {
                    println(e)
                }
            }
        }

        fun asyncPipeTest() {
            val pipe = object : NonBlockingWritePipe() {
                override fun writable() {
                }
            }

            async {
                val buffer = ByteBufferList()
                try {
                    pipe.read(buffer)
                    println(buffer.getString())
                    pipe.read(buffer)
                    println(buffer.getString())
                    pipe.read(buffer)
                    println(buffer.getString())
                }
                catch (e: Exception) {
                    println(e)
                }
            }

            val buffer = ByteBufferList()
            buffer.putString("test")
            pipe.write(buffer)

            buffer.putString("test2")
            pipe.write(buffer)

            pipe.end(IOException("whoopsy pipe"))
        }

        suspend fun AsyncNetworkContext.testTls() {
            val secureSocket = try {
                connectTls("google.com", 443)
            }
            catch (e: Exception) {
                println("connect failed")
                println(e)
                return
            }
            async {
                while (true) {
                    val buffer = ByteBufferList()
                    buffer.putString("GET / HTTP/1.1\r\n\r\n")
                    secureSocket.write(buffer)
                    sleep(5000)
                }
            }

            val buffer = ByteBufferList()
            while (secureSocket.read(buffer)) {
                println(buffer.string)
            }
        }

        suspend fun AsyncNetworkContext.testTls2() {
            val socket = connect("173.192.176.174", 443)
            val engine = SSLContext.getDefault().createSSLEngine("clockworkmod.com", 443)
            engine.useClientMode = true
            val secureSocket = tlsHandshake(socket, engine)
            async {
                while (true) {
                    val buffer = ByteBufferList()
                    buffer.putString("GET / HTTP/1.1\r\n\r\n")
                    secureSocket.write(buffer)
                    sleep(5000)
                }
            }

            val buffer = ByteBufferList()
            while (secureSocket.read(buffer)) {
                println(buffer.string)
            }
        }

        suspend fun AsyncNetworkContext.testHttp() {
            val client = AsyncHttpClient()
            val conscrypt = ConscryptMiddleware()
            conscrypt.install(client)

            for (i in 1..5) {
                val request = AsyncHttpRequest(URI("https://google.com"))
                println(request)
                println(i)
                val response = client.execute(request)
                val buffer = ByteBufferList()
                println(response)

                while (response.body!!(buffer)) {
                    println(buffer.string)
                }
            }

            println("done")
        }

        suspend fun AsyncNetworkContext.testServer() {
            val server = listen()
            println(server.localPort)

            for (socket in server.accept()) {
                async {
                    println("${socket.remoteAddress} ${socket.localPort}")
                    val buffer = ByteBufferList()
                    while (socket.read(buffer)) {
//                            val string = buffer.string
//                            println(string)
//                            buffer.putString(string.reversed())
//                        socket.write(buffer)
                        buffer.free()
                    }
                    println("done")
                }
            }
        }

        suspend fun AsyncNetworkContext.testHttpServer() {
            val httpServer = AsyncHttpServer {
                AsyncHttpResponse.OK(body = StringBody("ok ok ok!"))
            }

            httpServer.listen(listen(5555))
        }

        suspend fun AsyncNetworkContext.testConnect() {
            val buffer = ByteBufferList()
            val addr = InetSocketAddress("192.168.2.7", 5555)
            val socket = connect(addr)
            val interruptibleRead = InterruptibleRead(socket::read)
            while (true) {
//                    postDelayed(1000) {
//                        println("wakeup")
//                        interruptibleRead.interrupt()
//                    }
                interruptibleRead.read(buffer)
                if (buffer.hasRemaining())
                    println(buffer.string)
                else
                    println("got an empty buffer")
//                    sleep(5000)
            }

        }

        fun testScan() {
            val bb = ByteBufferList();
            bb.putString("test")
            bb.putString("fart")
            bb.putString("noob")
            val search = ByteBufferList()
            val ret = bb.getScan(search, "noo".toByteArray())
            println(search.string)
        }

        @JvmStatic fun main(args: Array<String>) {
            val provider = Conscrypt.newProvider()
            Security.insertProviderAt(provider, 1)

//            Security.insertProviderAt(Conscrypt.newProvider(), 1)

            val read: AsyncRead = {
                it.putString("poops");
                suspendCoroutine<Unit> {
                    AsyncNetworkContext.default.async {
                        sleep(1000)
                        it.resume(Unit)
                    }
                }
                true
            }

//            val discardServer = DiscardServer(5555)
//            discardServer.run()

            AsyncNetworkContext.default.async {
//                testHttpServer()
//                testTls2()
                testHttp()
//                testServer()

//                val pipe = object : NonBlockingWritePipe() {
//                    override fun writable() {
//                    }
//                }
//
//                async {
//                    val b = ByteBufferList()
//                    pipe.read(b)
//                    println("unblocked 1 with " + b.remaining())
//                }
//
//                async {
//                    val b = ByteBufferList()
//                    pipe.read(b)
//                    println("unblocked 2 with " + b.remaining())
//                }

            }

            Thread.sleep(10000000)
        }
    }
}
