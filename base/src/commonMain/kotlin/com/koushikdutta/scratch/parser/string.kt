package com.koushikdutta.scratch.parser

import com.koushikdutta.scratch.AsyncRead

@Deprecated("Use AsyncParser")
suspend fun readAllString(read: AsyncRead): String {
    return read.parse().readString()
}
