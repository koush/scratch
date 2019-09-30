package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.Buffers
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.buffers.WritableBuffers
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException


/**
 * This class pipes nonblocking write calls to AsyncRead.
 * The write call will return false when the high water mark is reached,
 * at which point the transport should be paused. The abstract writable method
 * will call writable once the reader has sufficiently drained the buffer.
 */
abstract class NonBlockingWritePipe {
    private var highWaterMark: Int = 65536
    private var needsWritable = false
    private val yielder = Cooperator()
    private val pending = ByteBufferList()
    private var eos = false
    private val result = AsyncResult<Unit>().finally(yielder::resume)
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

    fun write(buffer: ReadableBuffers): Boolean {
        buffer.get(pending)
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
        pending.get(buffer)
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
    fun writable() {
        yielder.resume()
    }

    abstract fun write(buffer: Buffers)

    suspend fun write(buffer: ReadableBuffers) {
        buffer.get(pendingOutput)
        write(pendingOutput)
        if (pendingOutput.isEmpty)
            return
        while (pendingOutput.hasRemaining()) {
            yielder.yield()
            write(pendingOutput)
        }
    }
}
