package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.filters.ChunkedInputPipe
import com.koushikdutta.scratch.filters.ChunkedOutputPipe
import com.koushikdutta.scratch.parser.readAllString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChunkedTests {
    @Test
    fun testChunked() {
        var data = ""

        async {
            val output = asyncWriter {
                write(ByteBufferList().putUtf8String("Hello World"))
                write(ByteBufferList().putUtf8String("Another Chunk"))
                write(ByteBufferList().putUtf8String("More Chunks"))
            }
                    .pipe(ChunkedOutputPipe)

            val input = AsyncReader(output).pipe(ChunkedInputPipe)
            data = readAllString(input)
        }

        assertEquals(data, "Hello WorldAnother ChunkMore Chunks")
    }
}