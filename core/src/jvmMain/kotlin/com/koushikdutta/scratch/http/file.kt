package com.koushikdutta.scratch.http

import com.koushikdutta.scratch.AsyncSliceable
import com.koushikdutta.scratch.event.NIOEventLoop
import com.koushikdutta.scratch.http.server.createSliceableResponse

suspend fun AsyncHttpRequest.createFileResponse(file: NIOEventLoop.AsyncFile): AsyncHttpResponse {
    if (!file.file.exists())
        return StatusCode.NOT_FOUND()
    return createSliceableResponse(file as AsyncSliceable)
}
