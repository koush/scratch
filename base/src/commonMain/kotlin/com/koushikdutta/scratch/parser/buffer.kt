package com.koushikdutta.scratch.parser

import com.koushikdutta.scratch.AsyncRead
import com.koushikdutta.scratch.buffers.ByteBufferList

@Deprecated("Use AsyncParser")
suspend fun readAllBuffer(read: AsyncRead): ByteBufferList {
    return read.parse().readBuffer()
}