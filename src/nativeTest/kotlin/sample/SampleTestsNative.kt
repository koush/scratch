package sample

import com.koushikdutta.scratch.async
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.event.AsyncEventLoop
import com.koushikdutta.scratch.event.RemoteSocketAddress
import kotlin.test.Test
import kotlin.test.assertTrue

class SampleTestsNative {
    @Test
    fun testHello() {
        assertTrue("Native" in hello())
    }

    @Test
    fun testServer() {
        val loop = AsyncEventLoop()
        loop.scheduleWakeup(1000L)

        async {
            try {
                val socket = loop.connect(RemoteSocketAddress("127.0.0.1", 5555))
                println("connect success")
                val buffer = ByteBufferList()
                while (socket.read(buffer)) {
                    val str = buffer.readUtf8String()
                    println("${str.length}: $str")
                    buffer.putUtf8String(str)
                    socket.write(buffer)
                }
                println("done")
            }
            catch (exception: Throwable) {
                println("socket failed: $exception")
            }
            println("async done")
            // loop.stop()
        }

        loop.run()
    }
}