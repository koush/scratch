package com.koushikdutta.scratch.parser

import com.koushikdutta.scratch.AsyncRead
import com.koushikdutta.scratch.buffers.ByteBufferList

suspend fun readAllBuffer(read: AsyncRead): ByteBufferList {
    val ret = ByteBufferList()
    while (read(ret)) {
    }
    return ret
}