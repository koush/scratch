package com.koushikdutta.scratch.atomic

import com.koushikdutta.scratch.buffers.AllocatingBuffers
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.buffers.WritableBuffers

class FreezableBuffers: Freezable {
    internal val stack = FreezableStack<ByteBufferList, Int>(0) { accumulated, value ->
        accumulated + value.remaining()
    }
    internal val returned = AtomicReference<ByteBufferList?>(null)

    val remaining: Int
        get() = stack.accumulated

    fun read(into: WritableBuffers): Boolean? {
        if (stack.isImmutable)
            return null
        val ret = stack.clear<ByteBufferList?>(null) { collector, value ->
            if (collector != null) {
                collector.read(value)
                value.takeReclaimedBuffers(collector)
            }
            value
        }
        if (ret == null) {
            val returnedBuffers = returned.getAndSet(null)
            if (returnedBuffers != null) {
                returnedBuffers.takeReclaimedBuffers(into)
                reclaim(returnedBuffers)
            }
            return false
        }
        val hasData = ret.read(into)
        reclaim(ret)
        if (hasData)
            return true
        if (isImmutable)
            return null
        return false
    }

    internal fun reclaim(buffers: ByteBufferList) {
        buffers.free()
        while (!returned.compareAndSet(null, buffers)) {
            val found = returned.getAndSet(null)
            if (found != null)
                buffers.takeReclaimedBuffers(found)
        }
    }

    fun freeze() = stack.freeze()

    override val isFrozen: Boolean
        get() = stack.isFrozen

    override val isImmutable: Boolean
        get() = stack.isImmutable

    fun takeReclaimedBuffers(buffers: AllocatingBuffers) {
        val found = returned.getAndSet(null) ?: ByteBufferList()
        found.takeReclaimedBuffers(buffers)
        reclaim(found)
    }
}

fun ReadableBuffers.read(buffers: FreezableBuffers): Int? {
    // always read all the data, even if frozen. mimics the behavior of normal buffers.
    val data = buffers.returned.getAndSet(null) ?: ByteBufferList()
    read(data)
    val ret = buffers.stack.push(data)
    if (!ret.frozen)
        return ret.accumulate
    return null
}

fun AllocatingBuffers.takeReclaimedBuffers(buffers: FreezableBuffers) {
    val reclaimed = buffers.returned.getAndSet(null)
    if (reclaimed != null) {
        takeReclaimedBuffers(reclaimed)
        buffers.reclaim(reclaimed)
    }
}
