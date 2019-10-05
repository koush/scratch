package com.koushikdutta.scratch.filters

import com.koushikdutta.scratch.AsyncReaderPipe
import com.koushikdutta.scratch.buffers.ByteBufferList
import java.io.IOException

val CRLF = "\r\n".toByteArray()

val ChunkedInputPipe: AsyncReaderPipe = { reader ->
    val temp = ByteBufferList();

    reader@{ buffer ->
        if (!reader.readScan(temp, CRLF))
            throw IOException("stream ended before chunk length")
        val length = temp.string.trim().toInt(16)
        require(length >= 0) { "negative length chunk encountered" }
        if (length == 0)
            return@reader false
        if (!reader.readLength(buffer, length))
            throw IOException("read ended before chunk completed")
        if (!reader.readScan(temp, CRLF))
            throw IOException("CRLF expected following data chunk")
        temp.free()
        true
    }
}