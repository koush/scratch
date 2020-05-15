package com.koushikdutta.scratch.http

import com.koushikdutta.scratch.AsyncSliceable
import com.koushikdutta.scratch.event.NIOEventLoop
import com.koushikdutta.scratch.http.server.AsyncHttpResponseScope
import com.koushikdutta.scratch.http.server.createResponse

suspend fun AsyncHttpResponseScope.createResponse(file: NIOEventLoop.AsyncFile): AsyncHttpResponse {
    if (!file.file.exists())
        return StatusCode.NOT_FOUND()
    return createResponse(file as AsyncSliceable)
}
