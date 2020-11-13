package com.koushikdutta.scratch.parser

import com.koushikdutta.scratch.AsyncRead

suspend fun readAllString(read: AsyncRead): String {
    return readAllBuffer(read).readUtf8String()
}
