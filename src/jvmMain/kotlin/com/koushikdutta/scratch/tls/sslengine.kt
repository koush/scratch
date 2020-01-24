package com.koushikdutta.scratch.tls

import com.koushikdutta.scratch.buffers.AllocationTracker
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.WritableBuffers
import javax.net.ssl.SSLEngineResult

// extension methods for unwrap with Buffers. manages overflows and allocations.
fun SSLEngine.unwrap(src: ByteBufferList, dst: WritableBuffers, tracker: AllocationTracker = AllocationTracker()): SSLEngineResult {
    tracker.finishTracking()
    while (true) {
        val unfiltered = if (src.hasRemaining()) src.readFirst() else ByteBufferList.EMPTY_BYTEBUFFER
        val hasMultipleBuffers = src.hasRemaining()
        val result = dst.putAllocatedBuffer(tracker.requestNextAllocation()) {
            val before = it.remaining()
            val ret = unwrap(unfiltered, it)
            val bytesProduced = before - it.remaining()
            // track the allocation to estimate future allocation needs
            tracker.trackDataUsed(bytesProduced)
            ret
        }
        src.addFirst(unfiltered)

        if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
            // allow the loop to continue
            tracker.minAlloc *= 2
            continue
        }
        else if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
            // attempt retry without reading the underlying input
            if (hasMultipleBuffers) {
                src.add(src.readByteBuffer())
                continue
            }
            return result
        }

        return result
    }
}

// extension methods for wrap with Buffers. manages overflows and allocations.
fun SSLEngine.wrap(src: ByteBufferList, dst: WritableBuffers, tracker: AllocationTracker = AllocationTracker()): SSLEngineResult {
    tracker.finishTracking()
    while (true) {
        val unencrypted = src.readAll()
        val result = dst.putAllocatedBuffer(tracker.requestNextAllocation()) {
            val before = it.remaining()
            val ret = wrap(unencrypted, it)
            val bytesProduced = before - it.remaining()
            // track the allocation to estimate future allocation needs
            tracker.trackDataUsed(bytesProduced)
            ret
        }

        // add unused unencrypted data back to the wrap buffer
        src.addAll(*unencrypted)

        if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
            // allow the loop to continue
            tracker.minAlloc *= 2
            continue
        }
        return result
    }
}
