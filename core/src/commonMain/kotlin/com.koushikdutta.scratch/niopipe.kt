package com.koushikdutta.scratch

import com.koushikdutta.scratch.async.startSafeCoroutine
import com.koushikdutta.scratch.atomic.*
import com.koushikdutta.scratch.buffers.Buffers
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.buffers.WritableBuffers


/**
 * This class pipes nonblocking write calls to AsyncRead.
 * After the high water mark is reached and write returns false, the writable lambda
 * will be invoked to let the writer know that the pipe is once again ready for data.
 * The pipe write will always fully succeed, and always take any available data written,
 * even if the write returns false. Ignoring the high water mark may result in out of memory errors.
 */
class NonBlockingWritePipe(private val highWaterMark: Int = 65536, private val writable: suspend NonBlockingWritePipe.() -> Unit) {
    // baton data is true for a write, false for read to verify read is not called erroneously.
    private val baton = Baton<Boolean>()
    private val pending = FreezableBuffers()
    private val needsWritable = AtomicBoolean()

    fun end(throwable: Throwable): Boolean {
        pending.freeze()
        return baton.raiseFinish(throwable) {
            // eat exceptions
            it?.finished != true
        }
    }

    fun end(): Boolean {
        pending.freeze()
        return baton.finish(true) {
            // eat exceptions
            it?.finished != true
        }
    }

    val hasEnded: Boolean
        get() = baton.isFinished

    /**
     * The write call will return false when the high water mark is reached,
     * at which point the transport should be paused. The abstract writable method
     * will be called once the reader has sufficiently drained the buffer.
     */
    fun write(buffer: ReadableBuffers): Boolean {
        // provide the data and resume any readers.

        // use spin lock to ensure a write message pending is pending
        while (true) {
            val read = baton.toss(true) {
                // this is a synchronization point between the write and read.
                // check this to guarantee that this is resuming a read to
                // so as not to feed data after the baton has closed.

                if (it?.finished == true)
                    null
                else if (it?.getOrThrow() == true)
                    -1
                else
                    buffer.read(pending)
            }

            if (read == -1)
                continue

            // the read coroutine will have synchronously finished
            // reading the buffer and may be reclaimed.
            buffer.takeReclaimedBuffers(pending)

            // check if writable needs to be invoked
            val setNeedsWritable = read != null && pending.remaining >= highWaterMark
            if (setNeedsWritable)
                needsWritable.set(true)

            return !setNeedsWritable
        }
    }

    suspend fun read(buffer: WritableBuffers): Boolean {
        val result = pending.read(buffer)
        // if the read failed, the pipe is closing.
        if (result == null) {
            // wait for the baton to finish or throw/finish
            if (baton.pass(false) { it.getOrThrow() && !it.resumed })
                throw AsyncDoubleReadException()
            return !baton.isFinished
        }

        // successful read drained the buffer
        if (needsWritable.getAndSet(false)) {
            writable()
            return true
        }

        if (result)
            return true

        if (baton.pass(false) { it.isSuccess && !it.getOrThrow() && !it.resumed })
            throw AsyncDoubleReadException()

        return true
    }
}

fun AsyncRead.buffer(highWaterMark: Int): AsyncRead {
    val self = this
    val buffer = ByteBufferList()
    val writable: NonBlockingWritePipe.() -> Unit = {
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
    val pipe = NonBlockingWritePipe(highWaterMark) { writable() }
    writable(pipe)

    return pipe::read
}

/**
 * This class pipes AsyncWrite calls to another AsyncWrite call.
 * The writer lambda writes as much data as the transport can handle
 * and returns the unsent data in the given buffer.
 * Upon returning unsent data, the transport is responsible for calling writable
 * when it is ready to resume sending data.
 */
open class BlockingWritePipe(private val writer: suspend BlockingWritePipe.(buffer: Buffers) -> Unit) {
    private val baton = Baton<Unit>()
    private val pending = ByteBufferList()
    private val writeLock = AtomicThrowingLock {
        AsyncDoubleWriteException()
    }

    fun writable() {
        baton.take(Unit)
    }

    val hasEnded
        get() = baton.isFinished

    fun close(): Boolean = close(IOException("pipe closed"))
    fun close(exception: Throwable): Boolean {
        return baton.raiseFinish(exception) {
            // eat exceptions
            it?.finished != true
        }
    }

    suspend fun write(buffer: ReadableBuffers) {
        writeLock {
            try {
                baton.rethrow()
                if (!pending.hasRemaining())
                    buffer.read(pending)
                if (pending.hasRemaining()) {
                    writer(pending)
                    if (pending.hasRemaining())
                        baton.pass(Unit)
                }
                buffer.takeReclaimedBuffers(pending)
            }
            catch (throwable: Throwable) {
                close(throwable)
                throw throwable
            }
        }
    }
}
