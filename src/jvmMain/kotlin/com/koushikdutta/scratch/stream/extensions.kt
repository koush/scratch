package com.koushikdutta.scratch.stream

import com.koushikdutta.scratch.AsyncRead
import com.koushikdutta.scratch.NonBlockingWritePipe
import com.koushikdutta.scratch.buffers.ByteBufferList
import java.io.InputStream

fun InputStream.createAsyncRead(readSize: Int = 65536): AsyncRead {
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

    return pipe::read
}
