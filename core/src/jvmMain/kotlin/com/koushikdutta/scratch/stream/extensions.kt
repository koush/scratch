package com.koushikdutta.scratch.stream

import com.koushikdutta.scratch.AsyncAffinity
import com.koushikdutta.scratch.AsyncInput
import com.koushikdutta.scratch.NonBlockingWritePipe
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.WritableBuffers
import com.koushikdutta.scratch.event.closeQuietly
import java.io.InputStream

fun InputStream.createAsyncInput(readSize: Int = 65536): AsyncInput {
    val buffer = ByteBufferList()

    val start: NonBlockingWritePipe.() -> Unit = {
        val thread = Thread({
            try {
                while (true) {
                    val read = buffer.putAllocatedByteBuffer(readSize) {
                        val read = read(it.array(), it.arrayOffset() + it.position(), readSize)
                        if (read >= 0)
                            it.position(it.position() + read)
                        read
                    }

                    if (read < 0)
                        break

                    if (!write(buffer))
                        return@Thread
                }
                end()
            }
            catch (throwable: Throwable) {
                end(throwable)
            }
        }, "InputStreamThread")

        thread.start()
    }

    val pipe = NonBlockingWritePipe(readSize * 3) {
        start()
    }

    start(pipe)

    val stream = this

    return object : AsyncInput, AsyncAffinity by AsyncAffinity.NO_AFFINITY {
        override suspend fun read(buffer: WritableBuffers) = pipe.read(buffer)
        override suspend fun close() = closeQuietly(stream)
    }
}
