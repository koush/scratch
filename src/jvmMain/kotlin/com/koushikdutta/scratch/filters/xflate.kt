package com.koushikdutta.scratch.filters

import com.koushikdutta.scratch.AsyncPipe
import com.koushikdutta.scratch.AsyncPipeYield
import com.koushikdutta.scratch.AsyncRead
import com.koushikdutta.scratch.buffers.AllocationTracker
import com.koushikdutta.scratch.buffers.BuffersBufferWriter
import com.koushikdutta.scratch.buffers.ByteBufferList
import java.lang.AssertionError
import java.nio.ByteBuffer
import java.util.zip.Deflater
import java.util.zip.Inflater

private typealias setInput = (ByteArray, Int, Int) -> Unit
private typealias needsInput = () -> Boolean
private typealias finished = () -> Boolean
private typealias xflate = (ByteArray, Int, Int) -> Int
private typealias finish = () -> Unit

private suspend fun XflatePipe(read: AsyncRead, yield: AsyncPipeYield, needsInput: needsInput, setInput: setInput, finished: finished, xflate: xflate, finish: finish) {
    val allocator = AllocationTracker()
    val inputBuffer = ByteBufferList()
    val outputBuffer = ByteBufferList()

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
            val xflated = outputBuffer.putAllocatedBuffer(allocator.requestNextAllocation(), bufferWriter)

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
                outputBuffer.reclaim(recycle)
                recycle = b
                if (!b.hasRemaining())
                    continue
                setInput(b.array(), b.arrayOffset() + b.position(), b.remaining())
            }
        }
        outputBuffer.reclaim(recycle)
        yield(outputBuffer)
    }


}

val DeflatePipe: AsyncPipe = { read, yield ->
    val deflater = Deflater()
    XflatePipe(read, `yield`, deflater::needsInput, deflater::setInput, deflater::finished, deflater::deflate, deflater::finish)
}

val InflatePipe: AsyncPipe = { read, yield ->
    val inflater = Inflater()
    XflatePipe(read, `yield`, inflater::needsInput, inflater::setInput, inflater::finished, inflater::inflate, {})
}