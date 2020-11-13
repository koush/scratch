package com.koushikdutta.scratch.http

import com.koushikdutta.scratch.event.AsyncEventLoop
import com.koushikdutta.scratch.http.server.createSliceableResponse

suspend fun AsyncHttpRequest.createFileResponse(file: AsyncEventLoop.AsyncFile): AsyncHttpResponse {
    if (!file.file.exists())
        return StatusCode.NOT_FOUND()
    return createSliceableResponse(file.size(), slice = file::slice)
}
