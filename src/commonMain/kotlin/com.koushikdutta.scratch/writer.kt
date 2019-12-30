package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList

fun asyncWriter(block: suspend AsyncPipeIteratorScope.() -> Unit): AsyncRead {
    val buffer = ByteBufferList()

    val iterator = asyncIterator<Unit> {
        val scope = AsyncPipeIteratorScope(buffer, this)
        block(scope)
    }

    return read@{
        buffer.takeReclaimedBuffers(it)
        if (!iterator.hasNext())
            return@read false
        iterator.next()
        it.add(buffer)
        true
    }
}
