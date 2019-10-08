package com.koushikdutta.scratch.filters

import com.koushikdutta.scratch.AsyncPipe
import com.koushikdutta.scratch.AsyncRead
import com.koushikdutta.scratch.buffers.Allocator
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

private fun XflatePipe(read: AsyncRead, needsInput: needsInput, setInput: setInput, finished: finished, xflate: xflate, finish: finish): AsyncRead {
    val allocator = Allocator()
    val temp = ByteBufferList()

    return read@{ buffer ->
        // read input if needed
        if (needsInput() && temp.isEmpty) {
            if (!read(temp)) {
                if (finished())
                    return@read false
                // finish caps off the xflate with the fin bytes
                finish()
            }
        }

        var recycle: ByteBuffer? = null
        var output = allocator.allocate()

        while (true) {
            val xflated = xflate(output.array(), output.arrayOffset() + output.position(), output.remaining())

            if (xflated == 0) {
                // the xflate produced nothing, so maybe feed it input
                if (!temp.hasRemaining()) {
                    if (!needsInput())
                        throw AssertionError("xflate needsInput expected")
                    break
                }

                // queue up another buffer
                val b = ByteBufferList.deepCopyIfDirect(temp.remove())
                ByteBufferList.reclaim(recycle)
                recycle = b
                if (!b.hasRemaining())
                    continue
                setInput(b.array(), b.arrayOffset() + b.position(), b.remaining())
            }

            allocator.track(xflated.toLong())

            output.position(output.position() + xflated)

            if (!output.hasRemaining()) {
                output.flip()
                buffer.add(output)
                output = allocator.allocate()
            }
        }
        output.flip()
        buffer.add(output)

        if (recycle != null)
            ByteBufferList.reclaim(recycle!!)

        true
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