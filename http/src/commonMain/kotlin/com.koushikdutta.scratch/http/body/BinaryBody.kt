package com.koushikdutta.scratch.http.body

import com.koushikdutta.scratch.AsyncRead
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.createByteBuffer
import com.koushikdutta.scratch.createReader
import com.koushikdutta.scratch.http.AsyncHttpMessageContent

open class BinaryBody(val read: AsyncRead, override val contentType: String = "application/octet-stream", override val contentLength: Long? = null) : AsyncHttpMessageContent, AsyncRead by read {
    override suspend fun close() {
    }
}

class BufferBody(buffer: ByteBufferList, override val contentType: String = "application/octet-stream"): BinaryBody(buffer.createReader(), contentType, buffer.remaining().toLong()){
    constructor(bytes: ByteArray, contentType: String = "application/octet-stream") : this(ByteBufferList(createByteBuffer(bytes)), contentType)
}