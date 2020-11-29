package com.koushikdutta.scratch.http

import com.koushikdutta.scratch.AsyncInput

interface AsyncHttpMessageContent : AsyncInput {
    val contentType: String?
    val contentLength: Long?

    companion object {
        internal fun prepare(headers: Headers, body: AsyncHttpMessageContent?, sent: AsyncHttpMessageCompletion?): AsyncHttpMessageCompletion? {
            if (body == null)
                return sent

            if (headers.contentType == null)
                headers.contentType = body.contentType
            headers.contentLength = body.contentLength

            return {
                body.close()
                sent?.invoke(it)
            }
        }
    }
}
