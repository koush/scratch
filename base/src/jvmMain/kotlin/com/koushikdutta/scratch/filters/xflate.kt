package com.koushikdutta.scratch.filters

import com.koushikdutta.scratch.AsyncPipe
import com.koushikdutta.scratch.AsyncPipeIteratorScope
import com.koushikdutta.scratch.AsyncRead
import com.koushikdutta.scratch.buffers.AllocationTracker
import com.koushikdutta.scratch.buffers.BuffersBufferWriter
import com.koushikdutta.scratch.buffers.ByteBufferList
import java.nio.ByteBuffer
import java.util.zip.Deflater
import java.util.zip.Inflater

private typealias setInput = (ByteArray, Int, Int) -> Unit
private typealias needsInput = () -> Boolean
private typealias finished = () -> Boolean
private typealias xflate = (ByteArray, Int, Int) -> Int
private typealias finish = () -> Unit

private suspend fun AsyncPipeIteratorScope.XflatePipe(read: AsyncRead, needsInput: needsInput, setInput: setInput, finished: finished, xflate: xflate, finish: finish) {
    val allocator = AllocationTracker()
    val inputBuffer = ByteBufferList()

    val bufferWriter: BuffersBufferWriter<Int> = {
        val xflated = xflate(it.array(), it.arrayOffset() + it.position(), it.remaining())
        allocator.trackDataUsed(xflated)
        it.position(it.position() + xflated)
        xflated
    }

    while (true) {
        // read input if needed
        if (needsInput() && inputBuffer.isEmpty) {
            if (!read(inputBuffer)) {
                if (finished())
                    break
                // finish caps off the xflate with the fin bytes
                finish()
            }
        }

        // xflate whatever is in the temp buffer
        var recycle: ByteBuffer? = null
        while (true) {
            val xflated = buffer.putAllocatedBuffer(allocator.requestNextAllocation(), bufferWriter)

            if (xflated == 0) {
                // maybe we finished? quit for now and signal eos on next read.
                if (finished())
                    break

                // if this xflate did not finish, this implies that more input is necessary.
                if (!needsInput())
                    throw AssertionError("xflate needsInput expected")

                // wait for more buffers if necessary
                if (!inputBuffer.hasRemaining())
                    break

                // queue up another buffer for xflate
                val b = ByteBufferList.deepCopyIfDirect(inputBuffer.readFirst())
                // processed buffers should be reclaimed by the xflater for future allocations
                buffer.reclaim(recycle)
                recycle = b
                if (!b.hasRemaining())
                    continue
                setInput(b.array(), b.arrayOffset() + b.position(), b.remaining())
            }
        }
        buffer.reclaim(recycle)
        flush()
    }
}

val DeflatePipe: AsyncPipe = {
    val deflater = Deflater()
    XflatePipe(it, deflater::needsInput, deflater::setInput, deflater::finished, deflater::deflate, deflater::finish)
}

val InflatePipe: AsyncPipe = {
    val inflater = Inflater()
    XflatePipe(it, inflater::needsInput, inflater::setInput, inflater::finished, inflater::inflate, {})
}