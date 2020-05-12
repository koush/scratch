package com.koushikdutta.scratch.http.client.middleware

import com.koushikdutta.scratch.AsyncRead
import com.koushikdutta.scratch.AsyncReader
import com.koushikdutta.scratch.AsyncReaderPipe
import com.koushikdutta.scratch.filters.ChunkedInputPipe
import com.koushikdutta.scratch.http.Headers
import com.koushikdutta.scratch.http.StatusCode
import com.koushikdutta.scratch.http.client.AsyncHttpClientSession
import com.koushikdutta.scratch.http.contentLength
import com.koushikdutta.scratch.http.transferEncoding
import kotlin.math.min


fun createContentLengthPipe(contentLength: Long): AsyncReaderPipe {
    require(contentLength >= 0) { "negative content length received: $contentLength" }
    var length = contentLength

    return {
        while (length > 0L) {
            val toRead = min(Int.MAX_VALUE.toLong(), length)
            if (!it.readChunk(buffer, toRead.toInt()))
                throw Exception("stream ended before end of expected content length")
            length -= buffer.remaining()
            flush()
        }
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

fun getHttpBodyOrNull(headers: Headers, reader: AsyncReader, server: Boolean): AsyncRead? {
    val read: AsyncRead

    val contentLength = headers.contentLength
    if (contentLength != null) {
        if (contentLength == 0L)
            return null
        read = reader.pipe(createContentLengthPipe(contentLength))
    }
    else if ("chunked" == headers.transferEncoding) {
        read = reader.pipe(ChunkedInputPipe)
    }
    else if (server) {
        // handling client request:
        // if this a client body is being parsed by the server,
        // and the client has not indicated a request body via either transfer-encoding or
        // content-length, there is no body.
        return null
    }
    else {
        // handling server response:
        // no meaningful headers means server will write data and close the connection.
        read = { reader.read(it) }
    }

    return read
}

fun getHttpBody(headers: Headers, reader: AsyncReader, server: Boolean): AsyncRead {
    return getHttpBodyOrNull(headers, reader, server) ?: { false }
}

class AsyncBodyDecoder : AsyncHttpClientMiddleware() {
    override suspend fun onResponseStarted(session: AsyncHttpClientSession) {
        val statusCode = StatusCode.values().find { it.code == session.response!!.code }
        val body = if (statusCode?.hasBody == false)
            null
        else
            getHttpBodyOrNull(session.response!!.headers, session.socket!!.reader, false)

        session.response!!.body = if (body == null) {
            session.responseCompleted = true
            session.socket?.completion?.invoke(null);
            { false }
        }
        else {
            session.response!!.body = createEndWatcher(session.response!!.body!!) {
                session.responseCompleted = true
                session.socket?.completion?.invoke(null)
            }

            body
        }
    }
}
