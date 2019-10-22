package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.buffers.WritableBuffers

class AsyncWriterScope(private val pending: WritableBuffers, private val yielder: suspend() -> Unit) {
    suspend fun write(output: ReadableBuffers): Unit {
        output.read(pending)
        yielder()
    }
}

fun asyncWriter(block: suspend AsyncWriterScope.() -> Unit): AsyncRead {
    val pending = ByteBufferList()
    var eos = false


    val yielder = Cooperator()

    val scope = AsyncWriterScope(pending) {
        yielder.yield()
    }
    val result = AsyncResultHolder<Unit>()

    startSafeCoroutine {
        try {
            block(scope)
            eos = true
        }
        catch (exception: Throwable) {

        }
        finally {
            yielder.resume()
        }
    }

    return {
        result.rethrow()

        if (pending.isEmpty && !eos) {
            yielder.yield()
            result.rethrow()
        }

        pending.read(it)
        !eos
    }
}
