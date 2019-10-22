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
    private val yielder = Cooperator()
    private val pending = ByteBufferList()
    private var eos = false
    private val result = AsyncResultHolder<Unit>(yielder::resume)
    private val completion: Continuation<Unit> = Continuation(EmptyCoroutineContext) { completionResult ->
        check(!eos) { "NonBlockingOutputPipe has already been closed" }
        eos = true
        result.setComplete(completionResult)
    }

    fun end(throwable: Throwable) {
        completion.resumeWithException(throwable)
    }

    fun end() {
        completion.resume(Unit)
    }

    val hasEnded: Boolean
        get() = eos

    /**
     * The write call will return false when the high water mark is reached,
     * at which point the transport should be paused. The abstract writable method
     * will be called once the reader has sufficiently drained the buffer.
     */
    fun write(buffer: ReadableBuffers): Boolean {
        buffer.read(pending)
        yielder.resume()

        needsWritable = pending.remaining() >= highWaterMark
        return !needsWritable
    }

    fun write(buffer: ByteBuffer): Boolean {
        pending.add(buffer)
        yielder.resume()

        needsWritable = pending.remaining() >= highWaterMark
        return !needsWritable
    }

    protected abstract fun writable()

    suspend fun read(buffer: WritableBuffers): Boolean {
        // rethrow errors downstream
        result.rethrow()

        // check if we have something to read
        if (pending.isEmpty) {
            // eos?
            if (eos)
                return false

            // wait for something to come down
            yielder.yield()
            result.rethrow()

            // still empty? bail. empty data but not eos is valid.
            if (pending.isEmpty)
                return eos
        }

        // at this point the buffer must have something
        pending.read(buffer)
        if (needsWritable) {
            needsWritable = false
            writable()
        }
        return true
    }
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

    abstract fun write(buffer: Buffers)

    suspend fun write(buffer: ReadableBuffers) {
        buffer.read(pendingOutput)
        write(pendingOutput)
        if (pendingOutput.isEmpty)
            return
        while (pendingOutput.hasRemaining()) {
            yielder.yield()
            write(pendingOutput)
        }
    }
}
