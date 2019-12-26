package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException


/**
 * This class pipes nonblocking write calls to AsyncRead.
 */
abstract class NonBlockingWritePipe(private var highWaterMark: Int = 65536) {
    private var needsWritable = false
    // baton data is true for a write, false for read to verify read is not called erroneously.
    private val baton = Baton<Boolean>()
    private val pending = ByteBufferList()

    fun end(throwable: Throwable) {
        baton.raiseFinish(throwable)
    }

    fun end() {
        baton.finish(true)
    }

    /**
     * The write call will return false when the high water mark is reached,
     * at which point the transport should be paused. The abstract writable method
     * will be called once the reader has sufficiently drained the buffer.
     */
    fun write(buffer: ReadableBuffers): Boolean {
        // provide the data and resume any readers.
        baton.tossResult(true) {
            buffer.read(pending)
        }

        // after the coroutine finishes, take back the free buffers
        // and check the high water mark status
        val needsWritable = baton.synchronized {
            buffer.takeReclaimedBuffers(pending)
            if (!needsWritable)
                needsWritable = pending.remaining() >= highWaterMark
            needsWritable
        }

        return !needsWritable
    }

    protected abstract fun writable()

    suspend fun read(buffer: WritableBuffers): Boolean {
        val invokeWritable: Boolean =
        baton.synchronized {
            if (pending.read(buffer))
                return true

            if (needsWritable && !baton.isFinished) {
                needsWritable = false
                return@synchronized true
            }

            if (baton.isFinished) {
                baton.rethrow()
                return false
            }

            false
        }

        if (invokeWritable) {
            writable()
            return true
        }

        if (baton.passResult(false) { it.isSuccess && !it.value!! && !it.resumed })
            throw IOException("read called while another read was in progress")
        return true
    }
}

fun AsyncRead.buffer(highWaterMark: Int): AsyncRead {
    val self = this
    val pipe = object : NonBlockingWritePipe(highWaterMark) {
        val buffer = ByteBufferList()

        init {
            triggerRead()
        }

        fun triggerRead() {
            startSafeCoroutine {
                try {
                    while (true) {
                        if (!self(buffer)) {
                            end()
                            break;
                        }
                        if (!write(buffer))
                            break
                    }
                }
                catch (throwable: Throwable) {
                    end(throwable)
                }
            }
        }

        override fun writable() {
            triggerRead()
        }
    }

    return pipe::read
}

/**
 * This class pipes AsyncWrite calls to nonblocking write calls.
 * The abstract write method writes as much data as the transport can handle
 * and return the remaining in the given buffer. When the transport is ready,
 * call writable.
 */
abstract class BlockingWritePipe {
    private val yielder = Cooperator()
    private val pendingOutput = ByteBufferList()
    private var exception: Throwable? = null
    fun writable() {
        yielder.resume()
    }

    fun close() = close(IOException("pipe closed"))
    fun close(exception: Throwable) {
        if(this.exception != null)
            throw IllegalStateException("pipe already closed")
        this.exception = exception
        yielder.resumeWithException(exception)
    }

    protected abstract fun write(buffer: Buffers)

    suspend fun write(buffer: ReadableBuffers) {
        buffer.read(pendingOutput)
        write(pendingOutput)
        while (pendingOutput.hasRemaining()) {
            yielder.yield()
            write(pendingOutput)
        }
        buffer.takeReclaimedBuffers(pendingOutput)
    }
}
