package com.koushikdutta.scratch

import com.koushikdutta.scratch.http.client.createContentLengthPipe
import com.koushikdutta.scratch.stream.createAsyncRead
import org.junit.Test
import java.io.FileInputStream
import java.util.concurrent.Semaphore
import com.koushikdutta.scratch.TestUtils.Companion.countBytes
import kotlin.test.assertEquals

class StreamTests {
    @Test
    fun testInputStream() {
        val semaphore = Semaphore(0)

        var count = 0
        async {
            val input = FileInputStream("/dev/zero").createAsyncRead()
            val reader = AsyncReader(input)
            val read = reader.pipe(createContentLengthPipe(100000000))
            count = read.countBytes()

            semaphore.release()
        }

        semaphore.acquire()
        assertEquals(count, 100000000)
    }
}