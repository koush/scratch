package com.koushikdutta.scratch.parser

import com.koushikdutta.scratch.AsyncRead
import com.koushikdutta.scratch.buffers.ByteBufferList

class AsyncParser(val read: AsyncRead)

fun AsyncRead.parse(): AsyncParser {
    return AsyncParser(this)
}

suspend fun AsyncParser.readBuffer(): ByteBufferList {
    val ret = ByteBufferList()
    while (read(ret)) {
    }
    return ret
}

suspend fun AsyncParser.readString(): String {
    return readBuffer().readUtf8String()
}
