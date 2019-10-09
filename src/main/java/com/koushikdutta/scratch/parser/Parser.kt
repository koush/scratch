package com.koushikdutta.scratch.parser

import com.koushikdutta.scratch.AsyncReader
import java.io.IOException

internal class Parser {
    companion object {
        suspend fun ensureReadString(reader: AsyncReader, expected: String) {
            val found = reader.readString(expected.length)
            if (found != expected)
                throw IOException("Multipart expected $expected but found $found")
        }
    }
}