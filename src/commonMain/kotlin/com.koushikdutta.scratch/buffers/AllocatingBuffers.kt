package com.koushikdutta.scratch.buffers

interface AllocatingBuffers {
    fun reclaim(vararg buffers: ByteBuffer?)
    fun obtain(size: Int): ByteBuffer
    fun giveReclaimedBuffers(into: ArrayList<ByteBuffer>)
    fun takeReclaimedBuffers(from: AllocatingBuffers)
}
