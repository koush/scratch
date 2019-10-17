package sample

import com.koushikdutta.scratch.event.AsyncEventLoop
import com.koushikdutta.scratch.event.UvEventLoop.Companion.getInterfaceAddresses
import com.koushikdutta.scratch.event.getByName
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

}