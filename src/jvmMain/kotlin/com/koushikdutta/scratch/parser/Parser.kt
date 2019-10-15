package com.koushikdutta.scratch.parser

import com.koushikdutta.scratch.AsyncReader

internal class Parser {
    companion object {
        suspend fun ensureReadString(reader: AsyncReader, expected: String) {
            val found = reader.readUtf8String(expected.length)
            if (found != expected)
                throw Exception("Multipart expected $expected but found $found")
        }
    }
}