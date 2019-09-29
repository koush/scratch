package com.koushikdutta.scratch

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
    suspend fun read(buffer: WritableBuffers) {
        input(unfiltered)
        filter(unfiltered, filtered)
        // add the queued data
        outgoing.get(buffer)
        // add the new filtered data
        filtered.get(buffer)
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
            try {
                val result: Boolean
                try {
                    result = input(transient)
                }
                finally {
                    reading = false
                }
                readResume?.resume(ReadResult(true, result))
            }
            catch (t: Throwable) {
                readResume!!.resumeWithException(t)
            }
        }
    }

    private data class ReadResult(val succeeded: Boolean, val result: Boolean = false)

    suspend fun read(buffer: WritableBuffers): Boolean {
        val result = suspendCoroutine<ReadResult> read@{
            readResume = it
            readTransient()
        }

        readResume = null
        if (!result.succeeded)
            return true
        transient.get(buffer)
        return result.result
    }

    fun interrupt() {
        readResume?.resume(ReadResult(false))
    }
}