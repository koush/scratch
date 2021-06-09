package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.createByteBufferList
import org.junit.Test

class ReaderTests {
    @Test
    fun testReadlineExpectingFailureOnClose() {
        val pipes = createAsyncPipeSocketPair()

        var failed = false
        async {
            pipes.first.write("test".createByteBufferList())
            pipes.first.close()
        }

        async {
            val reader = AsyncReader(pipes.second)
            try {
                reader.readLine()
            }
            catch (throwable: ReadScanException) {
                failed = throwable.finalData.readUtf8String() == "test"
            }
        }

        assert(failed)
    }
}