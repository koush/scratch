package sample

import com.koushikdutta.scratch.async
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.event.AsyncEventLoop
import com.koushikdutta.scratch.event.RemoteSocketAddress
import com.koushikdutta.scratch.uv.uv_loop_init
import com.koushikdutta.scratch.uv.uv_loop_t
import kotlinx.cinterop.cValue
import platform.posix.connect
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.native.concurrent.freeze
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SampleTestsNative {
    @Test
    fun testHello() {
        assertTrue("Native" in hello())
    }

    @Test
    fun testServer() {
        AsyncEventLoop().run {
            async {
                try {
                    val socket = connect(RemoteSocketAddress("127.0.0.1", 5555))
                    println("connect success")
                    val buffer = ByteBufferList()
                    while (socket.read(buffer)) {
                        val str = buffer.readUtf8String()
                        println("${str.length}: $str")
                        buffer.putUtf8String(str)
                        println("writing")
                        socket.write(buffer)
                        println("write done")
                    }
                    println("done")
                }
                catch (exception: Exception) {
                    println("connect failed: $exception")
                }
            }
        }
    }
}