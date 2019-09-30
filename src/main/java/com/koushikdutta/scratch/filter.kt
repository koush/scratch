package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.Buffers
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.buffers.WritableBuffers
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Manages filtering and buffering of data through a pipe.
 */
abstract class AsyncFilter {
    internal val unfiltered = ByteBufferList()
    // need separate buffers for filtered and outgoing as filtering may take place during
    // read/write operations
    internal val filtered = ByteBufferList()
    internal val outgoing = ByteBufferList()

    protected abstract fun filter(unfiltered: Buffers, filtered: Buffers)

    // invoke a filter on the pending data, even if it is empty.
    protected fun invokeFilter() {
        filter(unfiltered, filtered)
    }
}

abstract class AsyncWriteFilter(private val output: AsyncWrite): AsyncFilter() {
    suspend fun write(buffer: ReadableBuffers) {
        buffer.get(unfiltered)
        invokeFilter()

        while (filtered.hasRemaining()) {
            filtered.get(outgoing)
            output(outgoing)
        }
    }

    /**
     * Trigger an immediate filter and write. This is used in the event
     * the filter needs to synthesize data with no corresponding input.
     * The write must be able to handle concurrent invocations, such as using
     * AsyncHandler to perform write serialization.
     */
    fun invokeWrite() = async {
        write(ByteBufferList())
    }
}

abstract class AsyncReadFilter(private val input: AsyncRead) : AsyncFilter() {
    suspend fun read(buffer: WritableBuffers): Boolean {
        var ret = input(unfiltered)
        invokeFilter()
        // end of stream is only reached if the input reached end of stream,
        // and the outgoing and filtered buffers are empty.
        ret = outgoing.get(buffer) or ret
        ret = filtered.get(buffer) or ret
        return ret
    }

    /**
     * Perform a read call, but do not consume any data. Can be used to trigger
     * downstream reads.
     */
    suspend fun read(): Boolean {
        val temp = ByteBufferList()
        if (!read(temp))
            return false
        outgoing.add(temp)
        return true
    }
}

/**
 * Create an AsyncRead that can be interrupted.
 * The interrupted read will return true to indicate more data is present,
 * and the WritableBuffers will be unchanged.
 */
class InterruptibleRead(private val input: AsyncRead) {
    private var readResume: Continuation<ReadResult>? = null
    private val transient = ByteBufferList()
    private var reading = false

    fun readTransient() {
        synchronized(this) {
            if (reading)
                return
            reading = true
        }

        async {
            val result = try {
                try {
                    val ret = input(transient)
                    ReadResult(true, ret, null)
                } finally {
                    reading = false
                }
            } catch (t: Throwable) {
                ReadResult(true, null, t)
            }

            val resume: Continuation<ReadResult>?
            synchronized(this) {
                resume = readResume
                readResume = null
            }
            resume?.resume(result)
        }
    }

    private data class ReadResult(val succeeded: Boolean, val result: Boolean? = null, val throwable: Throwable? = null)

    suspend fun read(buffer: WritableBuffers): Boolean {
        check(readResume == null) { "read already in progress" }

        if (transient.hasRemaining()) {
            transient.get(buffer)
            return true
        }

        val result = suspendCoroutine<ReadResult> read@{
            readResume = it
            readTransient()
        }

        if (!result.succeeded)
            return true
        if (result.throwable != null)
            throw result.throwable
        transient.get(buffer)
        return result.result!!
    }

    fun interrupt() {
        val resume: Continuation<ReadResult>?
        synchronized(this) {
            resume = readResume
            readResume = null
        }
        resume?.resume(ReadResult(false))
    }
}