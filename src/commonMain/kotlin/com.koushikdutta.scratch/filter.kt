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
        val exit = synchronized(this) {
            if (reading)
                return@synchronized true
            reading = true
            false
        }
        if (exit)
            return

        startSafeCoroutine {
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

            val resume: Continuation<ReadResult>? = synchronized(this) {
                val resume = readResume
                readResume = null
                resume
            }
            resume?.resume(result)
        }
    }

    private data class ReadResult(val succeeded: Boolean, val result: Boolean? = null, val throwable: Throwable? = null)

    suspend fun read(buffer: WritableBuffers): Boolean {
        check(readResume == null) { "read already in progress" }

        if (transient.hasRemaining()) {
            transient.read(buffer)
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
        transient.read(buffer)
        return result.result!!
    }

    fun interrupt() {
        val resume: Continuation<ReadResult>? = synchronized(this) {
            val resume = readResume
            readResume = null
            resume
        }
        resume?.resume(ReadResult(false))
    }
}