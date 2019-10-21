package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.allocateByteBuffer
import com.koushikdutta.scratch.http.client.middleware.createContentLengthPipe
import com.koushikdutta.scratch.http.http2.write
import kotlin.random.Random

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