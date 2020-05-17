package com.koushikdutta.scratch.http.server

import com.koushikdutta.scratch.AsyncSliceable
import com.koushikdutta.scratch.http.*
import com.koushikdutta.scratch.http.body.BinaryBody

suspend fun AsyncHttpRequest.createSliceableResponse(input: AsyncSliceable, headers: Headers = Headers()): AsyncHttpResponse {
    val normalizedMethod = method.toUpperCase()
    val method = Methods.values().firstOrNull { it.name == normalizedMethod }
    if (method != Methods.GET && method != Methods.HEAD)
        return StatusCode.BAD_REQUEST()

    val totalLength = input.size()

    headers["Accept-Ranges"] = "bytes"

    val range = this.headers["Range"]
    var start = 0L
    var end: Long = totalLength - 1L
    var partial = false

    if (range != null) {
        var parts = range.split("=").toTypedArray()
        // Requested range not satisfiable
        if (parts.size != 2 || "bytes" != parts[0])
            return AsyncHttpResponse(ResponseLine(StatusCode.NOT_SATISFIABLE))

        parts = parts[1].split("-").toTypedArray()
        try {
            if (parts.size > 2) throw IllegalArgumentException()
            if (!parts[0].isEmpty()) start = parts[0].toLong()
            end = if (parts.size == 2 && !parts[1].isEmpty()) parts[1].toLong() else totalLength - 1
            partial = true
            headers["Content-Range"] = "bytes $start-$end/$totalLength"
        }
        catch (e: Throwable) {
            return AsyncHttpResponse(ResponseLine(StatusCode.NOT_SATISFIABLE))
        }
    }

    val status = if (!partial)
        StatusCode.OK
    else
        StatusCode.PARTIAL_CONTENT

    return if (method == Methods.GET) {
        val asyncInput = input.slice(start, end - start + 1)
        val body = BinaryBody(asyncInput::read, contentLength = totalLength)

        status(headers, body) {
            asyncInput.close()
        }
    }
    else {
        headers.contentLength = totalLength
        status(headers)
    }
}
