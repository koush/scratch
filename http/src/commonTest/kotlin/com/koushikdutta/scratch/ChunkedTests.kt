package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.filters.ChunkedInputPipe
import com.koushikdutta.scratch.filters.ChunkedOutputPipe
import com.koushikdutta.scratch.parser.parse
import com.koushikdutta.scratch.parser.readString
import kotlin.test.Test
import kotlin.test.assertEquals
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
            data = input.parse().readString()

            // another read should still indicate eos
            assertTrue(!input(ByteBufferList()))
        }

        assertEquals(data, "Hello WorldAnother ChunkMore Chunks")
    }
}