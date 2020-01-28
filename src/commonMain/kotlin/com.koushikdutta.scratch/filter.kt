package com.koushikdutta.scratch

import com.koushikdutta.scratch.async.startSafeCoroutine
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.WritableBuffers


/**
 * Create an AsyncRead that can be interrupted.
 * The interrupted read will return true to indicate more data is present,
 * and the WritableBuffers will be unchanged.
 */
class InterruptibleRead(private val input: AsyncRead) {
    private val pipe = PipeSocket()
    private val baton = Baton<Unit>()

    init {
        startSafeCoroutine {
            val buffer = ByteBufferList()
            try {
                baton.pass(Unit)
                while (buffer.hasRemaining() || input(buffer)) {
                    pipe.write(buffer)
                }
            }
            catch (throwable: Throwable) {
                pipe.close(throwable)
                return@startSafeCoroutine
            }

            pipe.close()
        }
    }

    suspend fun read(buffer: WritableBuffers): Boolean {
        baton.toss(Unit)
        return pipe.read(buffer)
    }
    fun interrupt() {
        pipe.interruptRead()
    }
    fun readTransient() = pipe.interruptWrite()
}
