package com.koushikdutta.scratch.tls

import com.koushikdutta.scratch.buffers.AllocationTracker
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.WritableBuffers

actual typealias DefaultHostnameVerifier = OkHostnameVerifier

fun javax.net.ssl.SSLEngineResult.Status.convert(): SSLEngineStatus {
    return when (this) {
        javax.net.ssl.SSLEngineResult.Status.CLOSED -> SSLEngineStatus.CLOSED
        javax.net.ssl.SSLEngineResult.Status.OK -> SSLEngineStatus.OK
        javax.net.ssl.SSLEngineResult.Status.BUFFER_OVERFLOW -> throw AssertionError("unexpected BUFFER_OVERFLOW")
        javax.net.ssl.SSLEngineResult.Status.BUFFER_UNDERFLOW -> SSLEngineStatus.BUFFER_UNDERFLOW
    }
}

fun javax.net.ssl.SSLEngineResult.HandshakeStatus.convert(): SSLEngineHandshakeStatus {
    return when (this) {
        javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_TASK -> SSLEngineHandshakeStatus.NEED_TASK
        javax.net.ssl.SSLEngineResult.HandshakeStatus.FINISHED -> SSLEngineHandshakeStatus.FINISHED
        javax.net.ssl.SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING -> SSLEngineHandshakeStatus.FINISHED
        javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> SSLEngineHandshakeStatus.NEED_UNWRAP
        javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_WRAP -> SSLEngineHandshakeStatus.NEED_WRAP
        else -> throw IllegalStateException("unknown handshake status: $this")
    }
}

fun javax.net.ssl.SSLEngineResult.convert(): SSLEngineResult {
    return SSLEngineResult(status.convert(), handshakeStatus.convert())
}

actual fun SSLEngine.runHandshakeTask() {
    delegatedTask?.run()
}

actual fun SSLEngine.checkHandshakeStatus(): SSLEngineHandshakeStatus {
    return handshakeStatus.convert()
}

// extension methods for unwrap with Buffers. manages overflows and allocations.
actual fun SSLEngine.unwrap(src: ByteBufferList, dst: WritableBuffers, tracker: AllocationTracker): SSLEngineResult {
    tracker.finishTracking()
    while (true) {
        val unfiltered = if (src.hasRemaining()) src.readFirst() else ByteBufferList.EMPTY_BYTEBUFFER
        val hasMultipleBuffers = src.hasRemaining()
        val result = dst.putAllocatedBuffers(tracker.requestNextAllocation()) {
            val ret = unwrap(unfiltered, it)
            // track the allocation to estimate future allocation needs
            tracker.trackDataUsed(ret.bytesProduced())
            ret
        }
        src.addFirst(unfiltered)

        if (result.status == javax.net.ssl.SSLEngineResult.Status.BUFFER_OVERFLOW) {
            // allow the loop to continue
            tracker.minAlloc *= 2
            continue
        }
        else if (result.status == javax.net.ssl.SSLEngineResult.Status.BUFFER_UNDERFLOW) {
            // attempt retry without reading the underlying input
            if (hasMultipleBuffers) {
                src.add(src.readByteBuffer())
                continue
            }
            return result.convert()
        }

        return result.convert()
    }
}

// extension methods for wrap with Buffers. manages overflows and allocations.
actual fun SSLEngine.wrap(src: ByteBufferList, dst: WritableBuffers, tracker: AllocationTracker): SSLEngineResult {
    tracker.finishTracking()
    while (true) {
        val result = dst.putAllocatedBuffer(tracker.requestNextAllocation()) {
            val before = it.remaining()
            val ret = src.readBuffers { unencrypted ->
                wrap(unencrypted, it)
            }
            val bytesProduced = before - it.remaining()
            // track the allocation to estimate future allocation needs
            tracker.trackDataUsed(bytesProduced)
            ret
        }

        if (result.status == javax.net.ssl.SSLEngineResult.Status.BUFFER_OVERFLOW) {
            // allow the loop to continue
            tracker.minAlloc *= 2
            continue
        }
        return result.convert()
    }
}
