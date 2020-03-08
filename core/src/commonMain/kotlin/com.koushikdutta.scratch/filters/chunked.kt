package com.koushikdutta.scratch.filters

import com.koushikdutta.scratch.AsyncPipe
import com.koushikdutta.scratch.AsyncReaderPipe
import com.koushikdutta.scratch.buffers.ByteBufferList

/*
POST / HTTP/1.1
Host: localhost:5555
User-Agent: curl/7.54.0
Transfer-Encoding: chunked
Content-Type: application/x-www-form-urlencoded

f
payload to send
0


*/

val CRLF = byteArrayOf(0x0d, 0x0a)

val ChunkedInputPipe: AsyncReaderPipe = {
    // hexLength(variable length) + CRLF
    // data(hexLength) + CRLF
    // [repeat above]
    // hexLength(0) + CRLF      (termination)

    while (true) {
        if (!it.readScan(buffer, CRLF))
            throw Exception("stream ended before chunk length")
        val length = buffer.readUtf8String().trim().toInt(16)
        require(length >= 0) { "negative length chunk encountered" }
        if (!it.readLength(buffer, length))
            throw Exception("read ended before chunk completed")
        flush()
        if (!it.readScan(buffer, CRLF))
            throw Exception("CRLF expected following data chunk")
        if (length == 0)
            break
    }
}

val ChunkedOutputPipe: AsyncPipe = {
    val temp = ByteBufferList()

    while (it(temp)) {
        if (temp.isEmpty)
            continue
        buffer.putUtf8String("${temp.remaining().toString(16).toUpperCase()}\r\n")
        buffer.add(temp)
        buffer.putUtf8String("\r\n")
        flush()
    }
    buffer.putUtf8String("0\r\n\r\n")
    flush()
}
