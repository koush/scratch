package com.koushikdutta.scratch.http.body

import com.koushikdutta.scratch.AsyncRead
import com.koushikdutta.scratch.http.AsyncHttpMessageBody

class BinaryBody(override val read: AsyncRead, override val contentType: String = "application/binary") : AsyncHttpMessageBody {
    override val contentLength: Long?
        get() = null
}