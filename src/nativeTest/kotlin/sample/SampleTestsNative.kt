package sample

import com.koushikdutta.scratch.async
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.event.AsyncEventLoop
import com.koushikdutta.scratch.event.Inet4Address
import com.koushikdutta.scratch.receive
import platform.darwin.inet_ntoa
import kotlin.test.Test
import kotlin.test.assertTrue

private class TimeoutException : Exception()
private class ExpectedException: Exception()

class SampleTestsNative {
     @Test
     fun testHello() {
         assertTrue("Native" in hello())
     }

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


//     @Test
//     fun testPostDelayed() {
//         val networkContext = AsyncEventLoop()
//
//         val result = networkContext.async {
//             try {
//                 sleep(50)
//                 throw Exception("delay fail")
//             }
//             finally {
//                 networkContext.stop()
//             }
//         }
//
//         networkContext.postDelayed(1000) {
//             result.setComplete(Result.failure(TimeoutException()))
//             networkContext.stop()
//         }
//
//         try {
//             networkContext.run()
//             result.rethrow()
//             assert(false)
//         }
//         catch (exception: TimeoutException) {
//             assert(false)
//         }
//         catch (exception: Exception) {
//         }
//     }
//
//    @Test
//    fun testServer() {
//        val loop = AsyncEventLoop()
//        loop.scheduleWakeup(1000L)
//
//        async {
//            try {
//                val socket = loop.connect(RemoteSocketAddress("127.0.0.1", 5555))
//                println("connect success")
//                val buffer = ByteBufferList()
//                while (socket.read(buffer)) {
//                    val str = buffer.readUtf8String()
//                    println("${str.length}: $str")
//                    buffer.putUtf8String(str)
//                    socket.write(buffer)
//                }
//                println("done")
//            }
//            catch (exception: Throwable) {
//                println("socket failed: $exception")
//            }
//            println("async done")
//            loop.stop()
//        }
//
//        loop.run()
//    }

    @Test
    fun testLookup() = networkContextTest{
        val result = getAllByName("google.com")
        println(result)
    }

//    @Test
//    fun testReadException() = networkContextTest(true) {
//        val client = connect(RemoteSocketAddress(Inet4Address("127.0.0.1"), 5555))
//        val buffer = ByteBufferList()
//        client.read(buffer)
//        throw ExpectedException()
//    }
}