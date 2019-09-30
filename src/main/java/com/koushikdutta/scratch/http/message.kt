package com.koushikdutta.scratch.http

import java.net.URI

abstract class AsyncHttpMessage(val headers: Headers = Headers()) {
    protected abstract val messageLine: String
    open var protocol: String = "HTTP/1.1"
        protected set

    override fun toString(): String {
        return "${messageLine}\r\n${headers}\r\n"
    }
}

open class AsyncHttpRequest(val uri: URI, val method: String = "GET") : AsyncHttpMessage() {
    override val messageLine: String
        get() {
            var path = statusLinePath
            val query = statusLineQuery
            if (path == null || path.isEmpty())
                path = "/"
            if (query == null || query.isEmpty())
                return "$method $path $protocol"

            return "$method $path?$query $protocol"
        }

    protected open val statusLinePath: String?
        get() {
            return uri.rawPath
        }

    protected open  val statusLineQuery: String?
        get() {
            return uri.rawQuery
        }

    override var protocol: String = "HTTP/1.1"
}

class AsyncHttpResponse(override val messageLine: String, headers: Headers = Headers()) : AsyncHttpMessage(headers) {
    val code: Int
    val message: String

    init {
        val parts = messageLine.split(Regex(" "), 3)
        require(parts.size >= 2) { "invalid status line" }
        protocol = parts[0]
        code = Integer.parseInt(parts[1])
        message = if (parts.size == 3) parts[2] else ""
    }
}