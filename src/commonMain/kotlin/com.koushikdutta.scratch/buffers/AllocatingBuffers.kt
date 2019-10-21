package com.koushikdutta.scratch.buffers

interface AllocatingBuffers {
    fun reclaim(vararg buffers: ByteBuffer?)
    fun obtain(size: Int): ByteBuffer
    fun obtainAll(into: ArrayList<ByteBuffer>)
    fun takeAll(from: AllocatingBuffers)
}