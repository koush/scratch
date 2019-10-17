package com.koushikdutta.scratch.http.client.middleware

import com.koushikdutta.scratch.AsyncRead
import com.koushikdutta.scratch.AsyncReader
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.filters.ChunkedInputPipe
import com.koushikdutta.scratch.http.Headers
import com.koushikdutta.scratch.http.client.AsyncHttpClientSession
import com.koushikdutta.scratch.http.contentLength
import com.koushikdutta.scratch.http.transferEncoding
import com.koushikdutta.scratch.pipe
import kotlin.math.min


fun createContentLengthPipe(contentLength: Long, reader: AsyncReader): AsyncRead {
    require(contentLength >= 0) { "negative content length received: $contentLength" }

    var length = contentLength
    val temp = ByteBufferList()
    return read@{
        if (length == 0L)
            return@read false
        val toRead = min(Int.MAX_VALUE.toLong(), length)
        if (!reader.readChunk(temp, toRead.toInt()))
            throw Exception("stream ended before end of expected content length")
        length -= temp.remaining()
        temp.read(it)
        true
    }
}

fun createEndWatcher(read: AsyncRead, complete: suspend () -> Unit): AsyncRead {
    return {
        val ret = read(it)
        if (!ret)
            complete()
        ret
    }
}

fun getHttpBody(headers: Headers, reader: AsyncReader, server: Boolean): AsyncRead {
    var read: AsyncRead

    val contentLength = headers.contentLength
    if (contentLength != null) {
        read = createContentLengthPipe(contentLength, reader)
    }
    else if ("chunked" == headers.transferEncoding) {
        read = reader.pipe(ChunkedInputPipe)
    }
    else if (server) {
        // handling client request:
        // if this a client body is being parsed by the server,
        // and the client has not indicated a request body via either transfer-encoding or
        // content-length, there is no body.
        read = { false }
        return read
    }
    else {
        // handling server response:
        // no meaningful headers means server will write data and close the connection.
        read = { reader.read(it) }
    }

    // todo: inflate
//    if ("deflate" == headers.get("Content-Encoding"))
//        read = read.pipe(InflatePipe)

    return read
}

class AsyncBodyDecoder : AsyncHttpClientMiddleware() {
    override suspend fun onResponseStarted(session: AsyncHttpClientSession) {
        session.response!!.body = getHttpBody(session.response!!.headers, session.socketReader!!, false)

        session.response!!.body = createEndWatcher(session.response!!.body!!) {
            session.client.onResponseComplete(session)
        }
    }
}
