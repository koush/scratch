package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.http.client.middleware.createContentLengthPipe
import kotlin.random.Random

internal class ExpectedException: Exception()
internal class TimeoutException: Exception()

class TestUtils {
    companion object {
        fun createRandomRead(length: Int): AsyncRead {
            return createUnboundRandomRead().pipe(createContentLengthPipe(length.toLong()))
        }

        fun createUnboundRandomRead(): AsyncReader {
            val random = Random
            return AsyncReader {
                it.putAllocatedBytes(10000) { bytes: ByteArray, offset: Int ->
                    random.nextBytes(bytes, offset, offset + 10000)
                }
                true
            }
        }

        suspend fun AsyncRead.countBytes(): Int {
            var ret = 0
            val buf = ByteBufferList()
            while (this(buf)) {
                ret += buf.remaining()
                buf.free()
            }
            return ret
        }

        suspend fun AsyncSocket.countBytes(): Int {
            var ret = 0
            val buf = ByteBufferList()
            while (read(buf)) {
                ret += buf.remaining()
                buf.free()
            }
            return ret
        }
    }
}

suspend fun Collection<Promise<*>>.awaitAll() {
    for (promise in this) {
        promise.await()
    }
}