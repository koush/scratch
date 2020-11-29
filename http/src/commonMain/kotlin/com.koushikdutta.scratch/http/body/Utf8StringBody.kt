package com.koushikdutta.scratch.http.body

import com.koushikdutta.scratch.buffers.WritableBuffers
import com.koushikdutta.scratch.buffers.createByteBufferList
import com.koushikdutta.scratch.createReader
import com.koushikdutta.scratch.http.AsyncHttpMessageContent

class Utf8StringBody(string: String) : AsyncHttpMessageContent {
    private val buffer = string.createByteBufferList()
    private val input = buffer.createReader()

    override suspend fun read(buffer: WritableBuffers) = input.read(buffer)
    override suspend fun close() {
    }

    override val contentType: String? = "text/plain"
    override val contentLength: Long? = buffer.remaining().toLong()
}
