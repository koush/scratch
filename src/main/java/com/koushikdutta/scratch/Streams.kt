package com.koushikdutta.scratch

import java.io.IOException
import java.net.InetSocketAddress


typealias AsyncRead = suspend (buffer: WritableBuffers) -> Boolean
typealias AsyncWrite = suspend (buffer: ReadableBuffers) -> Unit
typealias AsyncPipe = (read: AsyncRead) -> AsyncRead

interface AsyncSocket {
    suspend fun await()
    suspend fun read(buffer: WritableBuffers): Boolean
    suspend fun write(buffer: ReadableBuffers): Unit
    suspend fun close()
}

typealias AsyncCodec = suspend (socket: AsyncSocket) -> AsyncSocket

fun AsyncRead.pipe(pipe: AsyncPipe): AsyncRead {
    return pipe(this)
}

class AsyncWriterScope(private val pending: WritableBuffers, private val yielder: suspend() -> Unit) {
    suspend fun write(output: ReadableBuffers): Unit {
        output.get(pending)
        yielder()
    }
}

fun asyncWriter(block: suspend AsyncWriterScope.() -> Unit): AsyncRead {
    val pending = ByteBufferList()
    var eos = false


    val yielder = Cooperator()

    val scope = AsyncWriterScope(pending, yielder::`yield`)
    val result = async {
        block(scope)
        eos = true
    }
    .finally(yielder::resume)

    return {
        result.rethrow()

        if (pending.isEmpty && !eos) {
            yielder.yield()
            result.rethrow()
        }

        pending.get(it)
        !eos
    }
}

fun ByteBufferList.reader(): AsyncRead {
    return {
        this.get(it)
        false
    }
}

suspend fun AsyncWrite.drain(buffer: ReadableBuffers) {
    while (buffer.hasRemaining()) {
        this(buffer)
    }
}

suspend fun AsyncRead.copy(asyncWrite: AsyncWrite) {
    val bytes = ByteBufferList()
    while (this(bytes)) {
        asyncWrite.drain(bytes)
    }
    // ensure final data in end of stream is also copied
    asyncWrite.drain(bytes)
}



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

        @JvmStatic fun main(args: Array<String>) {
//            asyncWriterTest()
//
//            asyncPipeTest()
//
//            testIterator()

            AsyncServer.default.async {
//                val server = listen()
//                println(server.localPort)
//
//                while (true) {
//                    val socket = server.accept()
//                    async {
//                        println("${socket.remoteAddress} ${socket.localPort}")
//                        val buffer = ByteBufferList()
//                        while (socket.read(buffer)) {
//                            val string = buffer.string
//                            println(string)
//                            buffer.putString(string.reversed())
//                            socket.write(buffer)
//                        }
//                    }
//                }

//                postDelayed({
//                    //                        interruptibleRead.wakeup()
//                    println("1")
//                }, 1000)
//                postDelayed({
//                    //                        interruptibleRead.wakeup()
//                    println("2")
//                }, 1002)
//                postDelayed({
//                    //                        interruptibleRead.wakeup()
//                    println("3")
//                }, 1003)
//                postDelayed({
//                    //                        interruptibleRead.wakeup()
//                    println("4")
//                }, 1004)


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


//                AsyncServer.default.async {
//                    val buffer = ByteBufferList()
//                    buffer.putString("GET / HTTP/1.1\r\n\r\n")
//
//                    val addr = InetSocketAddress("172.217.14.206", 443)
//                    val socket = connect(addr)
//                    val context = SSLContext.getInstance("Default")
////                    context.init(null, null, null)
//                    val engine = context.createSSLEngine("google.com", 443)
//                    engine.useClientMode = true
//                    val secureSocket = tls(engine.peerHost, engine)(socket)
//                    secureSocket.write(buffer)
//                    while (secureSocket.read(buffer)) {
//                        println(buffer.string)
//                    }
//                }

            }

            Thread.sleep(10000000)
        }
    }
}
