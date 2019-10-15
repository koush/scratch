package com.koushikdutta.scratch.http.body

import com.koushikdutta.scratch.AsyncRead
import com.koushikdutta.scratch.http.AsyncHttpMessageBody

class BinaryBody(override val contentType: String = "application/binary", override val read: AsyncRead) : AsyncHttpMessageBody {
    override val contentLength: Long?
        get() = null
}