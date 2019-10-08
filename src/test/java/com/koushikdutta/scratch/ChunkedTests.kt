package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.filters.ChunkedInputPipe
import com.koushikdutta.scratch.filters.ChunkedOutputPipe
import com.koushikdutta.scratch.parser.readAllString
import org.junit.Test

class ChunkedTests {
    @Test
    fun testChunked() {
        var data = ""

        async {
            val output = asyncWriter {
                write(ByteBufferList().putString("Hello World"))
                write(ByteBufferList().putString("Another Chunk"))
                write(ByteBufferList().putString("More Chunks"))
            }
                    .pipe(ChunkedOutputPipe)

            val input = AsyncReader(output).pipe(ChunkedInputPipe)
            data = readAllString(input)
        }

        assert(data == "Hello WorldAnother ChunkMore Chunks")
    }
}