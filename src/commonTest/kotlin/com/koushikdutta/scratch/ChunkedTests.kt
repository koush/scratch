package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.filters.ChunkedInputPipe
import com.koushikdutta.scratch.filters.ChunkedOutputPipe
import com.koushikdutta.scratch.parser.readAllString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class ChunkedTests {
    @Test
    fun testChunked() {
        var data = ""

        async {
            val output = asyncWriter {
                buffer.putUtf8String("Hello World")
                buffer.putUtf8String("Another Chunk")
                buffer.putUtf8String("More Chunks")
                flush()
            }
            .pipe(ChunkedOutputPipe)

            val input = AsyncReader(output).pipe(ChunkedInputPipe)
            data = readAllString(input)

            // another read should still indicate eos
            assertTrue(!input(ByteBufferList()))
        }

        assertEquals(data, "Hello WorldAnother ChunkMore Chunks")
    }
}