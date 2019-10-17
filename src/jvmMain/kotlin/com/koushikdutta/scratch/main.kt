package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.AsyncHttpResponse
import com.koushikdutta.scratch.http.OK
import com.koushikdutta.scratch.http.body.StringBody
import com.koushikdutta.scratch.http.client.AsyncHttpClient
import com.koushikdutta.scratch.http.client.middleware.ConscryptMiddleware
import com.koushikdutta.scratch.http.server.AsyncHttpServer
import com.koushikdutta.scratch.event.AsyncEventLoop
import com.koushikdutta.scratch.event.connect
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
                val buffer = ByteBufferList()
                buffer.putUtf8String("test")
                write(buffer)

                buffer.putUtf8String("test2")
                write(buffer)

                throw IOException("whoops")
            }

            async {
                val buffer = ByteBufferList()
                try {
                    reader(buffer)
                    println(buffer.readUtf8String())
                    reader(buffer)
                    println(buffer.readUtf8String())
                    reader(buffer)
                    println(buffer.readUtf8String())
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
                    println(buffer.readUtf8String())
                    pipe.read(buffer)
                    println(buffer.readUtf8String())
                    pipe.read(buffer)
                    println(buffer.readUtf8String())
                }
                catch (e: Exception) {
                    println(e)
                }
            }

            val buffer = ByteBufferList()
            buffer.putUtf8String("test")
            pipe.write(buffer)

            buffer.putUtf8String("test2")
            pipe.write(buffer)

            pipe.end(IOException("whoopsy pipe"))
        }

        suspend fun AsyncEventLoop.testTls() {
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
                    buffer.putUtf8String("GET / HTTP/1.1\r\n\r\n")
                    secureSocket.write(buffer)
                    sleep(5000)
                }
            }

            val buffer = ByteBufferList()
            while (secureSocket.read(buffer)) {
                println(buffer.readUtf8String())
            }
        }

        suspend fun AsyncEventLoop.testTls2() {
            val socket = connect("173.192.176.174", 443)
            val engine = SSLContext.getDefault().createSSLEngine("clockworkmod.com", 443)
            engine.useClientMode = true
            val secureSocket = tlsHandshake(socket, engine)
            async {
                while (true) {
                    val buffer = ByteBufferList()
                    buffer.putUtf8String("GET / HTTP/1.1\r\n\r\n")
                    secureSocket.write(buffer)
                    sleep(5000)
                }
            }

            val buffer = ByteBufferList()
            while (secureSocket.read(buffer)) {
                println(buffer.readUtf8String())
            }
        }

        suspend fun AsyncEventLoop.testHttp() {
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
                    println(buffer.readUtf8String())
                }
            }

            println("done")
        }

        suspend fun AsyncEventLoop.testServer() {
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

        suspend fun AsyncEventLoop.testHttpServer() {
            val httpServer = AsyncHttpServer {
                AsyncHttpResponse.OK(body = StringBody("ok ok ok!"))
            }

            httpServer.listen(listen(5555))
        }

        suspend fun AsyncEventLoop.testConnect() {
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
                    println(buffer.readUtf8String())
                else
                    println("got an empty buffer")
//                    sleep(5000)
            }

        }

        fun testScan() {
            val bb = ByteBufferList();
            bb.putUtf8String("test")
            bb.putUtf8String("fart")
            bb.putUtf8String("noob")
            val search = ByteBufferList()
            val ret = bb.readScan(search, "noo".toByteArray())
            println(search.readUtf8String())
        }

        @JvmStatic
        fun main(args: Array<String>) {
            println("OK")
            val provider = Conscrypt.newProvider()
            Security.insertProviderAt(provider, 1)

//            Security.insertProviderAt(Conscrypt.newProvider(), 1)

            val read: AsyncRead = {
                it.putUtf8String("poops");
                suspendCoroutine<Unit> {
                    AsyncEventLoop.default.async {
                        sleep(1000)
                        it.resume(Unit)
                    }
                }
                true
            }

//            val discardServer = DiscardServer(5555)
//            discardServer.run()

            AsyncEventLoop.default.async {
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
