package com.koushikdutta.scratch

import com.sun.org.apache.xpath.internal.operations.Bool
import java.lang.IllegalStateException
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

abstract class AsyncFilter {
    internal val unfiltered = ByteBufferList()
    internal val filtered = ByteBufferList()
    internal val outgoing = ByteBufferList()

    protected abstract fun filter(unfiltered: Buffers, filtered: Buffers)
}

abstract class AsyncWriteFilter(private val output: AsyncWrite): AsyncFilter() {
    suspend fun write(buffer: ReadableBuffers) {
        buffer.get(unfiltered)
        filter(unfiltered, filtered)
        filtered.get(outgoing)
        output(outgoing)
    }
}

abstract class AsyncReadFilter(private val input: AsyncRead) : AsyncFilter() {
    suspend fun read(buffer: WritableBuffers): Boolean {
        val ret = input(unfiltered)
        filter(unfiltered, filtered)
        // add the queued data
        outgoing.get(buffer)
        // add the new filtered data
        filtered.get(buffer)
        return ret
    }
}

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