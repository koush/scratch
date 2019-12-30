package com.koushikdutta.scratch

import com.koushikdutta.scratch.atomic.*
import com.koushikdutta.scratch.buffers.*


/**
 * This class pipes nonblocking write calls to AsyncRead.
 */
class NonBlockingWritePipe(private val highWaterMark: Int = 65536, private val affinity: AsyncAffinity? = null, val writable: NonBlockingWritePipe.() -> Unit) {
    // baton data is true for a write, false for read to verify read is not called erroneously.
    private val baton = Baton<Boolean>()
    private val pending = FreezableBuffers()
    private val needsWritable = AtomicBoolean()

    fun end(throwable: Throwable): Boolean {
        pending.freeze()
        return baton.raiseFinish(throwable)?.finished != true
    }

    fun end(): Boolean {
        pending.freeze()
        return baton.finish(true)?.finished != true
    }

    /**
     * The write call will return false when the high water mark is reached,
     * at which point the transport should be paused. The abstract writable method
     * will be called once the reader has sufficiently drained the buffer.
     */
    fun write(buffer: ReadableBuffers): Boolean {
        // provide the data and resume any readers.
        val read = baton.toss(true) {
            // this is a synchronization point between the write and read.
            // check this to guarantee that this is resuming a read to
            // so as not to feed data after the baton has closed.
            if (it?.finished != true)
                buffer.read(pending)
            else
                null
        }

        // the read coroutine will have synchronously finished
        // reading the buffer and may be reclaimed.
        buffer.takeReclaimedBuffers(pending)

        // check if writable needs to be invoked
        val setNeedsWritable = (read ?: Int.MAX_VALUE) >= highWaterMark
        if (setNeedsWritable) {
            needsWritable.getAndSet(true)
            return false
        }
        return true
    }

    suspend fun read(buffer: WritableBuffers): Boolean {
        val result = pending.read(buffer)
        // if the read failed, the pipe is closing.
        if (result == null) {
            // wait for the baton to finish or throw/finish
            if (baton.pass(false) { it.rethrow(); it.isSuccess && !it.value!! && !it.resumed })
                throw IOException("read cancelled by another read")
            return !baton.isFinished
        }
        else if (result) {
            // successful read drained the buffer
            return true
        }

        if (needsWritable.getAndSet(false)) {
            affinity?.await()
            writable()
            return true
        }

        if (baton.pass(false) { it.isSuccess && !it.value!! && !it.resumed })
            throw IOException("read cancelled by another read")

        return true
    }
}

fun AsyncRead.buffer(highWaterMark: Int): AsyncRead {
    val self = this
    val buffer = ByteBufferList()
    val pipe = NonBlockingWritePipe(highWaterMark) {
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
    pipe.writable(pipe)

    return pipe::read
}

/**
 * This class pipes AsyncWrite calls to nonblocking write calls.
 * The abstract write method writes as much data as the transport can handle
 * and returns the unsent data in the given buffer.
 * Upon returning unsent data, the transport is responsible for calling writable
 * when it is ready to resume sending data.
 */
class BlockingWritePipe(private val affinity: AsyncAffinity? = null, val writer: BlockingWritePipe.(buffer: Buffers) -> Unit) {
    private val baton = Baton<Unit>()
    private val pending = ByteBufferList()
    private val writeLock = AtomicThrowingLock {
        IOException("write already in progress")
    }

    fun writable() {
        baton.toss(Unit)
    }

    fun close(): Boolean = close(IOException("pipe closed"))
    fun close(exception: Throwable): Boolean {
        return baton.raiseFinish(exception)?.finished != true
    }

    suspend fun write(buffer: ReadableBuffers) {
        writeLock {
            try {
                buffer.read(pending)
                while (pending.hasRemaining()) {
                    affinity?.await()
                    writer(pending)
                    buffer.takeReclaimedBuffers(pending)

                    if (pending.hasRemaining())
                        baton.pass(Unit)
                }
            }
            catch (throwable: Throwable) {
                baton.raiseFinish(throwable)
                throw throwable
            }
        }
    }
}

class ThrottlingPipe(affinity: AsyncAffinity?, val throttle: () -> Boolean) {
    private val nonblocking: NonBlockingWritePipe = NonBlockingWritePipe(affinity = affinity) {
        blocking.writable()
    }
    private val blocking: BlockingWritePipe = BlockingWritePipe(affinity) writer@{
        if (!throttle())
            return@writer
        nonblocking.write(it)
    }

    suspend fun write(buffer: ReadableBuffers) = blocking.write(buffer)
    suspend fun read(buffer: WritableBuffers) = nonblocking.read(buffer)
}