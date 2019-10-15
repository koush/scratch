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

val ChunkedInputPipe: AsyncReaderPipe = { reader ->
    val temp = ByteBufferList();

    // hexLength + CRLF         *
    // data: hexLength + CRLF   *
    // hexLength(0) + CRLF      (termination)

    { buffer ->
        if (!reader.readScan(temp, CRLF))
            throw Exception("stream ended before chunk length")
        val length = temp.readUtf8String().trim().toInt(16)
        require(length >= 0) { "negative length chunk encountered" }
        if (!reader.readLength(buffer, length))
            throw Exception("read ended before chunk completed")
        if (!reader.readScan(temp, CRLF))
            throw Exception("CRLF expected following data chunk")
        temp.free()
        length != 0
    }
}

val ChunkedOutputPipe: AsyncPipe = { read ->
    val temp = ByteBufferList()
    var sentEos = false

    // hexLength + CRLF         *
    // data: hexLength + CRLF   *
    // hexLength(0) + CRLF      (termination)

    read@{ buffer ->
        if (!read(temp)) {
            if (!sentEos) {
                sentEos = true
                buffer.putUtf8String("0\r\n\r\n")
                return@read true
            }
            return@read false
        }

        // an empty read is valid, but an empty chunk is not. just trigger another read.
        if (temp.isEmpty)
            return@read true

        buffer.putUtf8String("${temp.remaining().toString(16).toUpperCase()}\r\n")
        buffer.add(temp)
        buffer.putUtf8String("\r\n")

        true
    }
}
