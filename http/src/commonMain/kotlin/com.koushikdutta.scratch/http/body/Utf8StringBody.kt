package com.koushikdutta.scratch.http.body

import com.koushikdutta.scratch.AsyncRead
import com.koushikdutta.scratch.buffers.createByteBufferList
import com.koushikdutta.scratch.http.AsyncHttpMessageBody
import com.koushikdutta.scratch.createReader

class Utf8StringBody(string: String) : AsyncHttpMessageBody {
    private val buffer = string.createByteBufferList()
    private val input = buffer.createReader()

    override val contentType: String? = "text/plain"
    override val contentLength: Long? = buffer.remaining().toLong()
    override val read: AsyncRead = input
}