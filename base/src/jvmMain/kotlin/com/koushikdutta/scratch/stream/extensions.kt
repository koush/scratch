package com.koushikdutta.scratch.stream

import com.koushikdutta.scratch.AsyncAffinity
import com.koushikdutta.scratch.AsyncInput
import com.koushikdutta.scratch.NonBlockingWritePipe
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.WritableBuffers
import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.Semaphore

fun InputStream.createAsyncInput(readSize: Int = 65536): AsyncInput {
    val stream = this

    val buffer = ByteBufferList()

    val semaphore = Semaphore(0)

    val pipe = NonBlockingWritePipe(readSize * 3) {
        semaphore.release()
    }

    val thread = Thread({
        Thread.sleep(1000L)
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

                if (!pipe.write(buffer)) {
                    semaphore.acquire()
                }
            }
            pipe.end()
        }
        catch (throwable: Throwable) {
            pipe.end(throwable)
        }
        finally {
            closeQuietly(stream)
        }
    }, "InputStreamThread")

    thread.start()


    return object : AsyncInput, AsyncAffinity by AsyncAffinity.NO_AFFINITY {
        override suspend fun read(buffer: WritableBuffers) = pipe.read(buffer)
        override suspend fun close() {
            closeQuietly(stream)
            semaphore.release()
        }
    }
}

fun Closeable.closeQuietly() {
    kotlin.runCatching { close() }
}

fun closeQuietly(vararg closeables: Closeable?) {
    for (closeable in closeables) {
        closeable?.closeQuietly()
    }
}
