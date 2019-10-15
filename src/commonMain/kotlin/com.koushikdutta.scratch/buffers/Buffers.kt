package com.koushikdutta.scratch.buffers

interface Buffers : ReadableBuffers, WritableBuffers {
    fun addFirst(b: ByteBuffer): Buffers
}
