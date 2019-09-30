package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.filters.ChunkedInputPipe
import com.koushikdutta.scratch.http.AsyncHttpResponse
import com.koushikdutta.scratch.http.Headers
import com.koushikdutta.scratch.net.AsyncNetworkContext
import java.io.IOException
import java.net.InetSocketAddress
import javax.net.ssl.SSLContext

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
            val addr = InetSocketAddress("172.217.14.206", 443)
            val socket = connect(addr)
            val context = SSLContext.getInstance("Default")
//                    context.init(null, null, null)
            val engine = context.createSSLEngine("google.com", 443)
            engine.useClientMode = true
            val secureSocket = try {
                tlsHandshake(socket, engine.peerHost, engine);
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

        suspend fun AsyncNetworkContext.testHttp() {
            val addr = InetSocketAddress("172.217.14.206", 443)
            val socket = connect(addr)
            val context = SSLContext.getInstance("Default")
//                    context.init(null, null, null)
            val engine = context.createSSLEngine("google.com", 443)
            engine.useClientMode = true
            val secureSocket = tlsHandshake(socket, engine.peerHost, engine)

            val buffer = ByteBufferList()
            buffer.putString("GET / HTTP/1.1\r\n\r\n")
            secureSocket.write(buffer)

            val reader = AsyncReader(secureSocket::read)
            val statusLine = reader.readScanString("\r\n").trim()
            val headers = Headers()
            while (true) {
                val headerLine = reader.readScanString("\r\n").trim()
                if (headerLine.isEmpty())
                    break
                headers.addLine(headerLine)
            }
            val response = AsyncHttpResponse(statusLine, headers)
            println(response)

            val chunked = (reader::read as AsyncRead).pipe(ChunkedInputPipe)
            while (chunked(buffer)) {
                println(buffer.string)
            }
        }

        suspend fun AsyncNetworkContext.testServer() {
            val server = listen()
            println(server.localPort)

            while (true) {
                val socket = server.accept()
                async {
                    println("${socket.remoteAddress} ${socket.localPort}")
                    val buffer = ByteBufferList()
                    while (socket.read(buffer)) {
                            val string = buffer.string
                            println(string)
                            buffer.putString(string.reversed())
                        socket.write(buffer)
                    }
                    println("done")
                }
            }
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

            AsyncNetworkContext.default.async {
                testHttp()
//                testServer()
            }

            Thread.sleep(10000000)
        }
    }
}
