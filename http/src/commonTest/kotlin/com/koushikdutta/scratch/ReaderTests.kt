package com.koushikdutta.scratch

import org.junit.Test

class ReaderTests {
    @Test
    fun testReadlineExpectingFailureOnClose() {
        val pipes = createAsyncPipeSocketPair()

        var failed = false
        async {
            pipes.first.close()
            val reader = AsyncReader(pipes.second)
            try {
                reader.readLine()
            }
            catch (throwable: Throwable) {
                failed = true
            }
        }

        assert(failed)
    }
}