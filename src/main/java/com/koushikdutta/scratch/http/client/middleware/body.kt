package com.koushikdutta.scratch.http.client.middleware

import com.koushikdutta.scratch.AsyncPipe
import com.koushikdutta.scratch.AsyncRead
import com.koushikdutta.scratch.AsyncReader
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.filters.ChunkedInputPipe
import com.koushikdutta.scratch.http.client.AsyncHttpClientSession
import kotlin.math.min


fun createContentLengthPipe(contentLength: Long, reader: AsyncReader): AsyncRead {
    var length = contentLength
    val temp = ByteBufferList()
    return {
        if (length == 0L) {
            false
        }
        else {
            val toRead = min(Int.MAX_VALUE.toLong(), length)
            reader.readChunk(temp, toRead.toInt())
            length -= temp.remaining()
            temp.get(it)
            true
        }
    }
}

class AsyncBodyDecoder : AsyncHttpClientMiddleware() {
    override suspend fun onResponseStarted(session: AsyncHttpClientSession) {
        val contentLengthHeader = session.response!!.headers.get("Content-Length")
        if (contentLengthHeader != null) {
            val contentLength = contentLengthHeader.toLong()
            session.response!!.body = createContentLengthPipe(contentLength, session.socketReader!!)
        }
        else if ("chunked" == session.response!!.headers.get("Transfer-Encoding")) {
            session.response!!.body = session.socketReader!!.pipe(ChunkedInputPipe)
        }

        val endWatcher: AsyncPipe = { read ->
            {
                val ret = read(it)
                if (ret)
                    session.client.onResponseComplete(session)
                ret
            }
        }

        session.response!!.body = endWatcher(session.response!!.body!!)
    }
}
