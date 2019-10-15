package com.koushikdutta.scratch.http.body

import com.koushikdutta.scratch.AsyncRead
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.http.AsyncHttpMessageBody
import com.koushikdutta.scratch.reader
import java.nio.charset.Charset

class StringBody(string: String, charset: Charset = Charsets.UTF_8) : AsyncHttpMessageBody {
    private val buffer = ByteBufferList(string.toByteArray(charset))
    private val input = buffer.reader()

    override val contentType: String? = "text/plain"
    override val contentLength: Long? = buffer.remaining().toLong()
    override val read: AsyncRead = input
}