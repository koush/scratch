package com.koushikdutta.scratch.atomic

import com.koushikdutta.scratch.buffers.AllocatingBuffers
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.ReadableBuffers

class AtomicBuffers {
    internal val stack = AtomicStack<ByteBufferList, Int>(0) { accumulated, value ->
        accumulated + value.remaining()
    }
    private val returned = AtomicReference<ByteBufferList?>(null)

    val remaining: Int
        get() = stack.accumulated

    fun read(): ByteBufferList {
        return stack.clear() { collector, value ->
            collector.read(value)
            value.takeReclaimedBuffers(collector)
            value
        } ?: obtain()
    }

    fun reclaim(buffers: ByteBufferList) {
        buffers.free()
        while (true) {
            val found = returned.swapNull(buffers)
            if (found == null)
                break
            buffers.takeReclaimedBuffers(found)
        }
    }

    fun obtain(): ByteBufferList {
        return returned.getAndSet(null) ?: ByteBufferList()
    }
}

fun ReadableBuffers.read(buffer: AtomicBuffers): Int {
    val data = buffer.obtain()
    read(data)
    return buffer.stack.push(data)
}

fun AllocatingBuffers.takeReclaimedBuffers(buffers: AtomicBuffers) {
    val reclaimed = buffers.obtain()
    takeReclaimedBuffers(reclaimed)
    buffers.reclaim(reclaimed)
}