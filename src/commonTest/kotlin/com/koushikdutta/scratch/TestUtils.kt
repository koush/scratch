package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.allocateByteBuffer
import com.koushikdutta.scratch.http.client.middleware.createContentLengthPipe
import com.koushikdutta.scratch.http.http2.write
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Semaphore
import kotlin.random.Random

internal class ExpectedException: Exception()
internal class TimeoutException: Exception()

class TestUtils {
    companion object {
        fun createRandomRead(length: Int): AsyncRead {
            val random = Random
            return  AsyncReader {
                it.putAllocatedBytes(10000) { bytes: ByteArray, offset: Int ->
                    random.nextBytes(bytes, offset, offset + 10000)
                }
                true
            }
            .pipe(createContentLengthPipe(length.toLong()))
        }

        suspend fun AsyncRead.count(): Int {
            var ret = 0
            val buf = ByteBufferList()
            while (this(buf)) {
                ret += buf.remaining()
                buf.free()
            }
            return ret
        }
    }
}

fun Deferred<*>.rethrow() {
    val e = getCompletionExceptionOrNull()
    if (e != null)
        throw e
}

suspend fun Semaphore.acquire(numPermits: Int) {
    for (i in 0 until numPermits) {
        acquire()
    }
}
